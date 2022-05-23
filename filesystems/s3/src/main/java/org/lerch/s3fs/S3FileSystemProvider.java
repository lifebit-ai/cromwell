package org.lerch.s3fs;

import org.apache.commons.lang3.tuple.ImmutablePair;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import org.lerch.s3fs.attribute.S3BasicFileAttributeView;
import org.lerch.s3fs.attribute.S3BasicFileAttributes;
import org.lerch.s3fs.attribute.S3PosixFileAttributeView;
import org.lerch.s3fs.attribute.S3PosixFileAttributes;
import org.lerch.s3fs.util.AttributesUtils;
import org.lerch.s3fs.util.Cache;
import org.lerch.s3fs.util.S3Utils;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.nio.file.spi.FileSystemProvider;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.google.common.collect.Sets.difference;
import static java.lang.String.format;
import static java.lang.Thread.*;
import static org.lerch.s3fs.AmazonS3Factory.*;
import static software.amazon.awssdk.services.s3.model.ServerSideEncryption.AES256;

/**
 *
 * Spec:
 * <p>
 * URI: s3://[endpoint]/{bucket}/{key} If endpoint is missing, it's assumed to
 * be the default S3 endpoint (s3.amazonaws.com)
 * </p>
 * <p>
 * FileSystem roots: /{bucket}/
 * </p>
 * <p>
 * Treatment of S3 objects: - If a key ends in "/" it's considered a directory
 * *and* a regular file. Otherwise, it's just a regular file. - It is legal for
 * a key "xyz" and "xyz/" to exist at the same time. The latter is treated as a
 * directory. - If a file "a/b/c" exists but there's no "a" or "a/b/", these are
 * considered "implicit" directories. They can be listed, traversed and deleted.
 * </p>
 * <p>
 * Deviations from FileSystem provider API: - Deleting a file or directory
 * always succeeds, regardless of whether the file/directory existed before the
 * operation was issued i.e. Files.delete() and Files.deleteIfExists() are
 * equivalent.
 * </p>
 * <p>
 * Future versions of this provider might allow for a strict mode that mimics
 * the semantics of the FileSystem provider API on a best effort basis, at an
 * increased processing cost.
 * </p>
 */
public class S3FileSystemProvider extends FileSystemProvider {
    private static final String AMAZON_S3_FACTORY_CLASS = "s3fs_amazon_s3_factory";

    private static final ConcurrentMap<String, S3FileSystem> fileSystems = new ConcurrentHashMap<>();
    private static final List<String> PROPS_TO_OVERLOAD = Arrays.asList(ACCESS_KEY, SECRET_KEY, REQUEST_METRIC_COLLECTOR_CLASS, CONNECTION_TIMEOUT, MAX_CONNECTIONS, MAX_ERROR_RETRY, PROTOCOL, PROXY_DOMAIN,
            PROXY_HOST, PROXY_PASSWORD, PROXY_PORT, PROXY_USERNAME, PROXY_WORKSTATION, SOCKET_SEND_BUFFER_SIZE_HINT, SOCKET_RECEIVE_BUFFER_SIZE_HINT, SOCKET_TIMEOUT,
            USER_AGENT, AMAZON_S3_FACTORY_CLASS, SIGNER_OVERRIDE, PATH_STYLE_ACCESS);

    private final S3Utils s3Utils = new S3Utils();
    private Cache cache = new Cache();

    private final Logger log = Logger.getLogger(this.getClass().getName());

    @Override
    public String getScheme() {
        return "s3";
    }

    @Override
    public FileSystem newFileSystem(URI uri, Map<String, ?> env) {
        validateUri(uri);
        // get properties for the env or properties or system
        Properties props = getProperties(uri, env);
        validateProperties(props);
        // try to get the filesystem by the key
        String key = getFileSystemKey(uri, props);
        if (!fileSystems.containsKey(key)) {
            synchronized (fileSystems) {
                if (!fileSystems.containsKey(key)) {
                    // create the filesystem with the final properties, store and return
                    S3FileSystem fileSystem = createFileSystem(uri, props);
                    fileSystems.put(fileSystem.getKey(), fileSystem);
                }
            }
        }

        return fileSystems.get(key);
    }

    private void validateProperties(Properties props) {
        Preconditions.checkArgument(
                (props.getProperty(ACCESS_KEY) == null && props.getProperty(SECRET_KEY) == null)
                        || (props.getProperty(ACCESS_KEY) != null && props.getProperty(SECRET_KEY) != null), "%s and %s should both be provided or should both be omitted",
                ACCESS_KEY, SECRET_KEY);
    }

    private Properties getProperties(URI uri, Map<String, ?> env) {
        Properties props = loadAmazonProperties();
        addEnvProperties(props, env);
        // and access key and secret key can be override
        String userInfo = uri.getUserInfo();
        if (userInfo != null) {
            String[] keys = userInfo.split(":");
            props.setProperty(ACCESS_KEY, keys[0]);
            if (keys.length > 1) {
                props.setProperty(SECRET_KEY, keys[1]);
            }
        }
        return props;
    }

    private String getFileSystemKey(URI uri) {
        return getFileSystemKey(uri, getProperties(uri, null));
    }

    /**
     * get the file system key represented by: the access key @ endpoint.
     * Example: access-key@s3.amazonaws.com
     * If uri host is empty then s3.amazonaws.com are used as host
     *
     * @param uri   URI with the endpoint
     * @param props with the access key property
     * @return String
     */
    protected String getFileSystemKey(URI uri, Properties props) {
        // we don`t use uri.getUserInfo and uri.getHost because secret key and access key have special chars
        // and dont return the correct strings
        String uriString = uri.toString().replace("s3://", "");
        String authority = null;
        int authoritySeparator = uriString.indexOf("@");

        if (authoritySeparator > 0) {
            authority = uriString.substring(0, authoritySeparator);
        }

        if (authority != null) {
            String host = uriString.substring(uriString.indexOf("@") + 1, uriString.length());
            int lastPath = host.indexOf("/");
            if (lastPath > -1) {
                host = host.substring(0, lastPath);
            }
            if (host.length() == 0) {
                host = "s3.amazonaws.com";
            }
            return authority + "@" + host;
        } else {
            String accessKey = (String) props.get(ACCESS_KEY);
            return (accessKey != null ? accessKey + "@" : "") +
                    (uri.getHost() != null ? uri.getHost() : "s3.amazonaws.com");
        }
    }

    protected void validateUri(URI uri) {
        Preconditions.checkNotNull(uri, "uri is null");
        Preconditions.checkArgument(uri.getScheme().equals(getScheme()), "uri scheme must be 's3': '%s'", uri);
    }

    protected void addEnvProperties(Properties props, Map<String, ?> env) {
        if (env == null)
            env = new HashMap<>();
        for (String key : PROPS_TO_OVERLOAD) {
            // but can be overloaded by envs vars
            overloadProperty(props, env, key);
        }

        for (String key : env.keySet()) {
            Object value = env.get(key);
            if (!PROPS_TO_OVERLOAD.contains(key)) {
                props.put(key, value);
            }
        }
    }

    /**
     * try to override the properties props with:
     * <ol>
     * <li>the map or if not setted:</li>
     * <li>the system property or if not setted:</li>
     * <li>the system vars</li>
     * </ol>
     *
     * @param props Properties to override
     * @param env   Map the first option
     * @param key   String the key
     */
    private void overloadProperty(Properties props, Map<String, ?> env, String key) {
        boolean overloaded = overloadPropertiesWithEnv(props, env, key);

        if (!overloaded) {
            overloaded = overloadPropertiesWithSystemProps(props, key);
        }

        if (!overloaded) {
            overloadPropertiesWithSystemEnv(props, key);
        }
    }

    /**
     * @return true if the key are overloaded by the map parameter
     */
    protected boolean overloadPropertiesWithEnv(Properties props, Map<String, ?> env, String key) {
        if (env.get(key) != null && env.get(key) instanceof String) {
            props.setProperty(key, (String) env.get(key));
            return true;
        }
        return false;
    }

    /**
     * @return true if the key are overloaded by a system property
     */
    public boolean overloadPropertiesWithSystemProps(Properties props, String key) {
        if (System.getProperty(key) != null) {
            props.setProperty(key, System.getProperty(key));
            return true;
        }
        return false;
    }

    /**
     * The system envs have preference over the properties files.
     * So we overload it
     *
     * @param props Properties
     * @param key   String
     * @return true if the key are overloaded by a system property
     */
    public boolean overloadPropertiesWithSystemEnv(Properties props, String key) {
        if (systemGetEnv(key) != null) {
            props.setProperty(key, systemGetEnv(key));
            return true;
        }
        return false;
    }

    /**
     * Get the system env with the key param
     *
     * @param key String
     * @return String or null
     */
    public String systemGetEnv(String key) {
        return System.getenv(key);
    }

    /**
     * Get existing filesystem based on a combination of URI and env settings. Create new filesystem otherwise.
     *
     * @param uri URI of existing, or to be created filesystem.
     * @param env environment settings.
     * @return new or existing filesystem.
     */
    public FileSystem getFileSystem(URI uri, Map<String, ?> env) {
        validateUri(uri);
        Properties props = getProperties(uri, env);
        String key = this.getFileSystemKey(uri, props); // s3fs_access_key is part of the key here.
        if (fileSystems.containsKey(key))
            return fileSystems.get(key);
        return newFileSystem(uri, env);
    }

    @Override
    public S3FileSystem getFileSystem(URI uri) {
        validateUri(uri);
        String key = this.getFileSystemKey(uri);
        if (fileSystems.containsKey(key)) {
            return fileSystems.get(key);
        } else {
            throw new FileSystemNotFoundException("S3 filesystem not yet created. Use newFileSystem() instead");
        }
    }

    private S3Path toS3Path(Path path) {
        Preconditions.checkArgument(path instanceof S3Path, "path must be an instance of %s", S3Path.class.getName());
        return (S3Path) path;
    }

    /**
     * Deviation from spec: throws FileSystemNotFoundException if FileSystem
     * hasn't yet been initialized. Call newFileSystem() first.
     * Need credentials. Maybe set credentials after? how?
     * TODO: we can create a new one if the credentials are present by:
     * s3://access-key:secret-key@endpoint.com/
     */
    @Override
    public Path getPath(URI uri) {
        FileSystem fileSystem = getFileSystem(uri);
        /**
         * TODO: set as a list. one s3FileSystem by region
         */
        return fileSystem.getPath(uri.getPath());
    }

    @Override
    public DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter) throws IOException {
        final S3Path s3Path = toS3Path(dir);
        return new DirectoryStream<Path>() {
            @Override
            public void close() throws IOException {
                // nothing to do here
            }

            @Override
            public Iterator<Path> iterator() {
                return new S3Iterator(s3Path);
            }
        };
    }

    @Override
    public InputStream newInputStream(Path path, OpenOption... options) throws IOException {
        S3Path s3Path = toS3Path(path);
        String key = s3Path.getKey();

        Preconditions.checkArgument(options.length == 0, "OpenOptions not yet supported: %s", ImmutableList.copyOf(options));
        Preconditions.checkArgument(!key.equals(""), "cannot create InputStream for root directory: %s", path);

        try {
            ResponseInputStream<GetObjectResponse> res = s3Path
                    .getFileSystem()
                    .getClient()
                    .getObject(GetObjectRequest
                            .builder()
                            .bucket(s3Path.getFileStore().name())
                            .key(key)
                            .build());

            if (res == null)
                throw new IOException(String.format("The specified path is a directory: %s", path));

            return res;
        } catch (S3Exception e) {
            if (e.statusCode() == 404)
                throw new NoSuchFileException(path.toString());
            // otherwise throws a generic IO exception
            throw new IOException(String.format("Cannot access file: %s", path), e);
        }
    }

    @Override
    public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
        S3Path s3Path = toS3Path(path);
        return new S3SeekableByteChannel(s3Path, options);
    }

    @Override
    public FileChannel newFileChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
        S3Path s3Path = toS3Path(path);
        return new S3FileChannel(s3Path, options);
    }

    /**
     * Deviations from spec: Does not perform atomic check-and-create. Since a
     * directory is just an S3 object, all directories in the hierarchy are
     * created or it already existed.
     */
    @Override
    public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
        S3Path s3Path = toS3Path(dir);
        Preconditions.checkArgument(attrs.length == 0, "attrs not yet supported: %s", ImmutableList.copyOf(attrs)); // TODO
        if (exists(s3Path))
            throw new FileAlreadyExistsException(format("target already exists: %s", s3Path));
        // create bucket if necessary
        Bucket bucket = s3Path.getFileStore().getBucket();
        String bucketName = s3Path.getFileStore().name();
        if (bucket == null) {
            s3Path.getFileSystem().getClient().createBucket(CreateBucketRequest.builder().bucket(bucketName).build());
        }
        // create the object as directory
        PutObjectRequest.Builder builder = PutObjectRequest.builder();
        String directoryKey = s3Path.getKey().endsWith("/") ? s3Path.getKey() : s3Path.getKey() + "/";
        builder.bucket(bucketName)
                .key(directoryKey)
                .serverSideEncryption(AES256.name())
                .contentLength(0L);
        s3Path.getFileSystem().getClient().putObject(builder.build(), RequestBody.fromBytes(new byte[0]));
    }

    @Override
    public void delete(Path path) throws IOException {
        S3Path s3Path = toS3Path(path);
        if (Files.notExists(s3Path))
            throw new NoSuchFileException("the path: " + this + " not exists");
        if (Files.isDirectory(s3Path) && Files.newDirectoryStream(s3Path).iterator().hasNext())
            throw new DirectoryNotEmptyException("the path: " + this + " is a directory and is not empty");

        String key = s3Path.getKey();
        String bucketName = s3Path.getFileStore().name();
        s3Path.getFileSystem().getClient().deleteObject(DeleteObjectRequest.builder().bucket(bucketName).key(key).build());
        // we delete the two objects (sometimes exists the key '/' and sometimes not)
        s3Path.getFileSystem().getClient().deleteObject(DeleteObjectRequest.builder().bucket(bucketName).key(key + "/").build());
    }

    @Override
    public void copy(Path source, Path target, CopyOption... options) throws IOException {
        if (isSameFile(source, target))
            return;

        final S3Path s3Source = toS3Path(source);
        final S3Path s3Target = toS3Path(target);

        final ImmutableSet<CopyOption> actualOptions = ImmutableSet.copyOf(options);
        verifySupportedOptions(EnumSet.of(StandardCopyOption.REPLACE_EXISTING), actualOptions);

        if (exists(s3Target) && !actualOptions.contains(StandardCopyOption.REPLACE_EXISTING)) {
            throw new FileAlreadyExistsException(format("target already exists: %s", target));
        }

        long objectSize = this.objectSize(s3Source);
        long threshold = 5L * 1024L * 1024L * 1024L; //5GB
        if (objectSize >= threshold) {
            // large file, do a multipart copy
            multiPartCopy(s3Source, objectSize, s3Target, options);

        } else {
            //do a normal copy
            String bucketNameOrigin = s3Source.getFileStore().name();
            String keySource = s3Source.getKey();
            String bucketNameTarget = s3Target.getFileStore().name();
            String keyTarget = s3Target.getKey();
            s3Source.getFileSystem()
                    .getClient()
                    .copyObject(CopyObjectRequest.builder()
                            .copySource(bucketNameOrigin + "/" + keySource)
                            .bucket(bucketNameTarget)
                            .key(keyTarget)
                            .serverSideEncryption(AES256.name())
                            .build());
        }
    }

    /**
     * Copy large files
     * https://docs.aws.amazon.com/AmazonS3/latest/API/API_UploadPartCopy.html
     *
     * @param source  the object being copied
     * @param target  the destination
     * @param options copy options
     */
    private void multiPartCopy(S3Path source, long objectSize, S3Path target, CopyOption... options) {
        log.info(() -> "Attempting multipart copy as part of call cache hit: source = " + source + ", objectSize = " + objectSize + ", target = " + target + ", options = " + Arrays.deepToString(options));

        S3Client s3Client = target.getFileSystem().getClient();

        final CreateMultipartUploadRequest createMultipartUploadRequest = CreateMultipartUploadRequest.builder()
                .bucket(target.getFileStore().name())
                .key(target.getKey())
                .serverSideEncryption(AES256.name())
                .build();
        final CreateMultipartUploadResponse createMultipartUploadResponse = s3Client.createMultipartUpload(createMultipartUploadRequest);
        final String uploadId = createMultipartUploadResponse.uploadId();

        // you can have at most 10K parts with at least one 5MB part
        long partSize = Math.max((objectSize / 10000L) + 1, 5 * 1024 * 1024);
        long bytePosition = 0;
        int partNum = 1;

        List<CompletableFuture<UploadPartCopyResponse>> uploadFutures = new ArrayList<>();

        /* if you set this number to a larger value then ensure the HttpClient has sufficient
           maxConnections (see org.lerch.s3fsAmazonS3Factory.getHttpClient)  */
        int THREADS = 500;

        log.info(() -> "Allocating work stealing pool with "+THREADS+" threads");
        final ExecutorService MULTIPART_OPERATION_EXECUTOR_SERVICE = Executors.newWorkStealingPool(THREADS);


        // Generate the copy part requests
        while (bytePosition < objectSize) {
            // The last part might be smaller than partSize, so check to make sure
            // that lastByte isn't beyond the end of the object.
            long lastByte = Math.min(bytePosition + partSize - 1, objectSize - 1);

            // make this effectively final so we can use it in a lambda
            long finalBytePosition = bytePosition;
            log.fine(() -> "Requesting copy of bytes from: " + finalBytePosition + " to: " + lastByte);

            int finalPartNum = partNum;
            try {
                CompletableFuture<UploadPartCopyResponse> uploadPartCopyResponseFuture = CompletableFuture.supplyAsync(() -> {
                    final UploadPartCopyRequest uploadPartCopyRequest = UploadPartCopyRequest.builder()
                            .uploadId(uploadId)
                            .copySource(source.getFileStore().name() + "/" + source.getKey())
                            .copySourceRange("bytes=" + finalBytePosition + "-" + lastByte)
                            .bucket(target.getFileStore().name())
                            .key(target.getKey())
                            .serverSideEncryption(AES256.name())
                            .partNumber(finalPartNum)
                            .build();
                    UploadPartCopyResponse uploadPartCopyResponse =  s3Client.uploadPartCopy(uploadPartCopyRequest);
                    if(! uploadPartCopyResponse.sdkHttpResponse().isSuccessful()) {
                        log.warning(() -> uploadPartCopyResponse.copyPartResult().toString());
                    }
                    return uploadPartCopyResponse;
                }, MULTIPART_OPERATION_EXECUTOR_SERVICE);

                uploadFutures.add(uploadPartCopyResponseFuture);

                bytePosition += partSize;
                partNum++;

            } catch (RejectedExecutionException e) {
                // catching this so we can log diagnostic information to the ERROR stream. Will rethrow it
                log.severe("A RejectedExecutionException has occurred while attempting to create an async UploadPartCopyRequest");
                log.severe(() -> "Was requesting copy of part number "+finalPartNum+" with bytes from "+finalBytePosition+" to "+lastByte);
                if (MULTIPART_OPERATION_EXECUTOR_SERVICE.isShutdown()) log.severe("ExecutorService has shutdown. A Multipart copy will not be possible");
                if (MULTIPART_OPERATION_EXECUTOR_SERVICE.isTerminated()) log.severe("ExecutorService has terminated. A Multipart copy will not be possible");
                Runtime runtime = Runtime.getRuntime();
                log.severe("Free Memory = "+runtime.freeMemory());
                log.severe("Allocated Memory = "+runtime.totalMemory());
                log.severe("Max Memory = "+runtime.maxMemory());

                // clean up so we don't get zombie threads
                log.info(() -> "Shutting down work stealing pool");
                MULTIPART_OPERATION_EXECUTOR_SERVICE.shutdown();

                log.throwing(S3FileSystemProvider.class.getName(), "multiPartCopy", e);
                throw e;
            } catch (SdkClientException e){
                log.severe(e.getMessage());
                e.printStackTrace();
                // clean up so we don't get zombie threads
                log.info(() -> "Shutting down work stealing pool");
                MULTIPART_OPERATION_EXECUTOR_SERVICE.shutdown();
                throw e;
            }

        }


        // Collect the futures into a list of completed parts
        List<CompletedPart> completedParts = IntStream.range(0, uploadFutures.size())
                .peek(i -> log.fine("Joining future " + i))
                .mapToObj(i -> ImmutablePair.of(i, uploadFutures.get(i).join()))
                .map(tuple -> CompletedPart.builder()
                        .partNumber(tuple.getLeft() + 1) //part numbers start at 1
                        .eTag(tuple.getRight().copyPartResult().eTag())
                        .build())
                .collect(Collectors.toList());

        log.info(() -> "Shutting down work stealing pool");
        MULTIPART_OPERATION_EXECUTOR_SERVICE.shutdown();

        // build a request to complete the upload
        final CompleteMultipartUploadRequest completeMultipartUploadRequest = CompleteMultipartUploadRequest.builder()
                .uploadId(uploadId)
                .bucket(target.getFileStore().name())
                .key(target.getKey())
                .multipartUpload(CompletedMultipartUpload.builder()
                        .parts(completedParts)
                        .build())
                .build();

        try {
            // make a request to complete the multipart upload
            final CompleteMultipartUploadResponse completeMultipartUploadResponse = s3Client.completeMultipartUpload(completeMultipartUploadRequest);

            log.info(() -> "Multipart copy complete with status code: "+completeMultipartUploadResponse.sdkHttpResponse().statusCode());
        } catch (AwsServiceException | SdkClientException e) {
            log.warning(() -> "An "+e.getClass().getName()+" with message "+e.getMessage()+
                    " occurred while completing the multipart upload. Will try again.");
            try {
                sleep(1234);
            } catch (InterruptedException interruptedException) {
                interruptedException.printStackTrace();
            }

            final CompleteMultipartUploadResponse completeMultipartUploadResponse = s3Client.completeMultipartUpload(completeMultipartUploadRequest);
            log.info(() -> "Multipart copy complete with status code: "+completeMultipartUploadResponse.sdkHttpResponse().statusCode());
        }
    }

    /**
     * Obtain the size of an s3 object using a HEAD operation
     *
     * @param object the object of interest
     * @return the size in bytes
     */
    private long objectSize(S3Path object) {

        S3Client s3Client = object.getFileSystem().getClient();
        final String bucket = object.getFileStore().name();
        final String key = object.getKey();
        final HeadObjectResponse headObjectResponse = s3Client.headObject(HeadObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build());

        Integer partCount = headObjectResponse.partsCount();
        if (partCount != null && partCount > 1) {
            //this is a multi-part object
            long totalLength = 0;

            for (int i = 1; i <= partCount; i++) {
                int finalI = i;
                totalLength += s3Client.headObject(builder -> builder.bucket(bucket).key(key).partNumber(finalI)).contentLength();
            }

            return totalLength;

        } else {
            return headObjectResponse.contentLength();
        }
    }


    @Override
    public void move(Path source, Path target, CopyOption... options) throws IOException {
        if (options != null && Arrays.asList(options).contains(StandardCopyOption.ATOMIC_MOVE))
            throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "Atomic not supported");
        copy(source, target, options);
        delete(source);
    }

    @Override
    public boolean isSameFile(Path path1, Path path2) throws IOException {
        return path1.isAbsolute() && path2.isAbsolute() && path1.equals(path2);
    }

    @Override
    public boolean isHidden(Path path) throws IOException {
        return false;
    }

    @Override
    public FileStore getFileStore(Path path) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void checkAccess(Path path, AccessMode... modes) throws IOException {
        S3Path s3Path = toS3Path(path);
        Preconditions.checkArgument(s3Path.isAbsolute(), "path must be absolute: %s", s3Path);
        if (modes.length == 0) {
            if (exists(s3Path))
                return;
            throw new NoSuchFileException(toString());
        }

        String key = s3Utils.getS3ObjectSummary(s3Path).key();
        String bucket = s3Path.getFileStore().name();
        S3AccessControlList accessControlList =
                new S3AccessControlList(bucket, key, s3Path.getFileSystem().getClient().getObjectAcl(GetObjectAclRequest.builder().bucket(bucket).key(key).build()).grants(), s3Path.getFileStore().getOwner());

        accessControlList.checkAccess(modes);
    }


    @Override
    @SuppressWarnings("unchecked")
    public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type, LinkOption... options) {
        S3Path s3Path = toS3Path(path);
        if (type == BasicFileAttributeView.class) {
            return (V) new S3BasicFileAttributeView(s3Path);
        } else if (type == PosixFileAttributeView.class) {
            return (V) new S3PosixFileAttributeView(s3Path);
        } else if (type == null) {
            throw new NullPointerException("Type is mandatory");
        } else {
            return null;
        }
    }

    @Override
    public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options) throws IOException {
        S3Path s3Path = toS3Path(path);
        if (type == BasicFileAttributes.class) {
            if (cache.isInTime(s3Path.getFileSystem().getCache(), s3Path.getFileAttributes())) {
                A result = type.cast(s3Path.getFileAttributes());
                s3Path.setFileAttributes(null);
                return result;
            } else {
                S3BasicFileAttributes attrs = s3Utils.getS3FileAttributes(s3Path);
                s3Path.setFileAttributes(attrs);
                return type.cast(attrs);
            }
        } else if (type == PosixFileAttributes.class) {
            if (s3Path.getFileAttributes() instanceof PosixFileAttributes &&
                    cache.isInTime(s3Path.getFileSystem().getCache(), s3Path.getFileAttributes())) {
                A result = type.cast(s3Path.getFileAttributes());
                s3Path.setFileAttributes(null);
                return result;
            }

            S3PosixFileAttributes attrs = s3Utils.getS3PosixFileAttributes(s3Path);
            s3Path.setFileAttributes(attrs);
            return type.cast(attrs);
        }

        throw new UnsupportedOperationException(format("only %s or %s supported", BasicFileAttributes.class, PosixFileAttributes.class));
    }

    @Override
    public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) throws IOException {
        if (attributes == null) {
            throw new IllegalArgumentException("Attributes null");
        }

        if (attributes.contains(":") && !attributes.contains("basic:") && !attributes.contains("posix:")) {
            throw new UnsupportedOperationException(format("attributes %s are not supported, only basic / posix are supported", attributes));
        }

        if (attributes.equals("*") || attributes.equals("basic:*")) {
            BasicFileAttributes attr = readAttributes(path, BasicFileAttributes.class, options);
            return AttributesUtils.fileAttributeToMap(attr);
        } else if (attributes.equals("posix:*")) {
            PosixFileAttributes attr = readAttributes(path, PosixFileAttributes.class, options);
            return AttributesUtils.fileAttributeToMap(attr);
        } else {
            String[] filters = new String[]{attributes};
            if (attributes.contains(",")) {
                filters = attributes.split(",");
            }
            Class<? extends BasicFileAttributes> filter = BasicFileAttributes.class;
            if (attributes.startsWith("posix:")) {
                filter = PosixFileAttributes.class;
            }
            return AttributesUtils.fileAttributeToMap(readAttributes(path, filter, options), filters);
        }
    }

    @Override
    public void setAttribute(Path path, String attribute, Object value, LinkOption... options) throws IOException {
        throw new UnsupportedOperationException();
    }

    // ~~

    /**
     * Create the fileSystem
     *
     * @param uri   URI
     * @param props Properties
     * @return S3FileSystem never null
     */
    public S3FileSystem createFileSystem(URI uri, Properties props) {
        return new S3FileSystem(this, getFileSystemKey(uri, props), getS3Client(uri, props), uri.getHost());
    }

    public S3FileSystem createFileSystem(URI uri, Properties props, S3Client client) {
        return new S3FileSystem(this, getFileSystemKey(uri, props), client, uri.getHost());
    }

    protected S3Client getS3Client(URI uri, Properties props) {
        return getAmazonS3Factory(props).getS3Client(uri, props);
    }

    protected AmazonS3Factory getAmazonS3Factory(Properties props) {
        if (props.containsKey(AMAZON_S3_FACTORY_CLASS)) {
            String amazonS3FactoryClass = props.getProperty(AMAZON_S3_FACTORY_CLASS);
            try {
                return (AmazonS3Factory) Class.forName(amazonS3FactoryClass).newInstance();
            } catch (InstantiationException | IllegalAccessException | ClassNotFoundException | ClassCastException e) {
                throw new S3FileSystemConfigurationException("Configuration problem, couldn't instantiate AmazonS3Factory (" + amazonS3FactoryClass + "): ", e);
            }
        }
        return new AmazonS3ClientFactory();
    }

    /**
     * find /amazon.properties in the classpath
     *
     * @return Properties amazon.properties
     */
    public Properties loadAmazonProperties() {
        Properties props = new Properties();
        // http://www.javaworld.com/javaworld/javaqa/2003-06/01-qa-0606-load.html
        // http://www.javaworld.com/javaqa/2003-08/01-qa-0808-property.html
        try (InputStream in = currentThread().getContextClassLoader().getResourceAsStream("amazon.properties")) {
            if (in != null)
                props.load(in);
        } catch (IOException e) {
            // If amazon.properties can't be loaded that's ok.
        }
        return props;
    }

    // ~~~

    private <T> void verifySupportedOptions(Set<? extends T> allowedOptions, Set<? extends T> actualOptions) {
        Sets.SetView<? extends T> unsupported = difference(actualOptions, allowedOptions);
        Preconditions.checkArgument(unsupported.isEmpty(), "the following options are not supported: %s", unsupported);
    }

    /**
     * check that the paths exists or not
     *
     * @param path S3Path
     * @return true if exists
     */
    boolean exists(S3Path path) {
        S3Path s3Path = toS3Path(path);
        try {
            s3Utils.getS3ObjectSummary(s3Path);
            return true;
        } catch (NoSuchFileException e) {
            return false;
        }
    }

    public void close(S3FileSystem fileSystem) {
        if (fileSystem.getKey() != null && fileSystems.containsKey(fileSystem.getKey()))
            fileSystems.remove(fileSystem.getKey());
    }

    public boolean isOpen(S3FileSystem s3FileSystem) {
        return fileSystems.containsKey(s3FileSystem.getKey());
    }

    /**
     * only 4 testing
     */

    @VisibleForTesting
    public static ConcurrentMap<String, S3FileSystem> getFilesystems() {
        return fileSystems;
    }

    public Cache getCache() {
        return cache;
    }

    public void setCache(Cache cache) {
        this.cache = cache;
    }
}
