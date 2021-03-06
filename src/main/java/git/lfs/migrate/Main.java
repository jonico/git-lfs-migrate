package git.lfs.migrate;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import org.apache.http.HttpStatus;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.ssl.SSLContexts;
import org.eclipse.jgit.errors.InvalidPatternException;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.bozaro.gitlfs.client.AuthHelper;
import ru.bozaro.gitlfs.client.BatchUploader;
import ru.bozaro.gitlfs.client.Client;
import ru.bozaro.gitlfs.client.auth.AuthProvider;
import ru.bozaro.gitlfs.client.auth.BasicAuthProvider;
import ru.bozaro.gitlfs.client.exceptions.ForbiddenException;
import ru.bozaro.gitlfs.client.exceptions.RequestException;
import ru.bozaro.gitlfs.common.data.*;
import ru.bozaro.gitlfs.common.data.Error;

import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.GeneralSecurityException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Entry point.
 *
 * @author a.navrotskiy
 */
public class Main {
  @NotNull
  private static final Logger log = LoggerFactory.getLogger(Main.class);

  public static void main(@NotNull String[] args) throws Exception {
    final CmdArgs cmd = new CmdArgs();
    final JCommander jc = new JCommander(cmd);
    jc.parse(args);
    if (cmd.help) {
      jc.usage();
      return;
    }
    final long time = System.currentTimeMillis();
    final Client client;
    if (cmd.lfs != null) {
      client = createClient(new BasicAuthProvider(URI.create(cmd.lfs)), cmd);
    } else if (cmd.git != null) {
      client = createClient(AuthHelper.create(cmd.git), cmd);
    } else {
      client = null;
    }
    if (!checkLfsAuthenticate(client)) {
      return;
    }
    if (cmd.checkLfs) {
      if (client == null) {
        log.error("Git LFS server is not defined.");
      }
      return;
    }
    String[] globs = cmd.globs.toArray(new String[cmd.globs.size()]);
    if (cmd.globFile != null) {
      globs = Stream.concat(Arrays.stream(globs),
          Files.lines(cmd.globFile)
              .map(String::trim)
              .filter(s -> !s.isEmpty())
      ).toArray(String[]::new);
    }
    try {
      processRepository(cmd.src, cmd.dst, cmd.cache, client, cmd.writeThreads, cmd.uploadThreads, globs);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof RequestException) {
        final RequestException cause = (RequestException) e.getCause();
        log.error("HTTP request failure: {}", cause.getRequestInfo());
      }
      throw e;
    }
    log.info("Convert time: {}", System.currentTimeMillis() - time);
  }

  @NotNull
  private static Client createClient(@NotNull AuthProvider auth, @NotNull CmdArgs cmd) throws GeneralSecurityException {
    final HttpClientBuilder httpBuilder = HttpClients.custom();
    httpBuilder.setUserAgent("git-lfs-migrate");
    if (cmd.noCheckCertificate) {
      httpBuilder.setSSLHostnameVerifier((hostname, session) -> true);
      httpBuilder.setSSLContext(SSLContexts.custom()
          .loadTrustMaterial((chain, authType) -> true)
          .build());
    }
    return new Client(auth, httpBuilder.build());
  }

  private static boolean checkLfsAuthenticate(@Nullable Client client) throws IOException {
    if (client == null)
      return true;
    final Meta meta = new Meta("0123456789012345678901234567890123456789012345678901234567890123", 42);
    try {
      BatchRes response = client.postBatch(
          new BatchReq(Operation.Upload, Collections.singletonList(
              meta
          ))
      );
      if (response.getObjects().size() != 1) {
        log.error("LFS server: Invalid response for test batch request");
      }
      Error error = response.getObjects().get(0).getError();
      if (error != null) {
        if (error.getCode() == HttpStatus.SC_FORBIDDEN) {
          log.error("LFS server: Upload access denied");
        } else {
          log.error("LFS server: Upload denied with error: " + error.getMessage());
        }
      }
      log.info("LFS server: OK");
      return true;
    } catch (ForbiddenException e) {
      log.error("LFS server: Access denied", e);
      return false;
    } catch (IOException e) {
      log.info("LFS server: Batch API request exception", e);
    }
    try {
      client.getMeta(meta.getOid());
      log.error("LFS server: Unsupported batch API");
    } catch (IOException ignored) {
      log.error("LFS server: Invalid base URL");
    }
    return false;
  }

  public static void processRepository(@NotNull Path srcPath, @NotNull Path dstPath, @NotNull Path cachePath, @Nullable Client client, int writeThreads, int uploadThreads, @NotNull String... globs) throws IOException, InterruptedException, ExecutionException, InvalidPatternException {
    removeDirectory(dstPath);
    Files.createDirectories(dstPath);

    final Repository srcRepo = new FileRepositoryBuilder()
        .setMustExist(true)
        .setGitDir(srcPath.toFile()).build();
    final Repository dstRepo = new FileRepositoryBuilder()
        .setMustExist(false)
        .setGitDir(dstPath.toFile()).build();

    try (DB cache = DBMaker.fileDB(cachePath.resolve("git-lfs-migrate.mapdb").toFile())
        .fileMmapEnableIfSupported()
        .checksumHeaderBypass()
        .make()) {
      final GitConverter converter = new GitConverter(cache, dstPath, globs);
      dstRepo.create(true);
      // Load all revision list.
      ConcurrentMap<TaskKey, ObjectId> converted = new ConcurrentHashMap<>();
      try (HttpUploader uploader = createHttpUploader(srcRepo, client, uploadThreads)) {
        log.info("Converting object without dependencies in " + writeThreads + " threads...");
        Deque<TaskKey> pass2 = processWithoutDependencies(converter, srcRepo, dstRepo, converted, uploader, writeThreads);
        log.info("Converting object with dependencies in single thread...");
        processSingleThread(converter, srcRepo, dstRepo, converted, uploader, pass2);
      }

      log.info("Recreating refs...");
      for (Map.Entry<String, Ref> ref : srcRepo.getAllRefs().entrySet()) {
        RefUpdate refUpdate = dstRepo.updateRef(ref.getKey());
        final ObjectId oldId = ref.getValue().getObjectId();
        final ObjectId newId = converted.get(new TaskKey(GitConverter.TaskType.Simple, "", oldId));
        refUpdate.setNewObjectId(newId);
        refUpdate.update();
        log.info("  convert ref: {} -> {} ({})", oldId.getName(), newId.getName(), ref.getKey());
      }
    } finally {
      dstRepo.close();
      srcRepo.close();
    }
  }

  @Nullable
  private static HttpUploader createHttpUploader(@NotNull Repository repository, @Nullable Client client, int uploadThreads) {
    return client == null ? null : new HttpUploader(repository, client, uploadThreads);
  }

  private static void processSingleThread(@NotNull GitConverter converter, @NotNull Repository srcRepo, @NotNull Repository dstRepo, @NotNull Map<TaskKey, ObjectId> converted, @Nullable HttpUploader uploader, @NotNull Deque<TaskKey> queue) throws IOException {
    try (ProgressReporter reporter = new ProgressReporter("processed", new AtomicLong(queue.size()), null)) {
      final ObjectInserter inserter = dstRepo.newObjectInserter();
      final ObjectReader reader = srcRepo.newObjectReader();
      while (!queue.isEmpty()) {
        final TaskKey taskKey = queue.pop();
        if (!converted.containsKey(taskKey)) {
          boolean taskReady = true;
          for (TaskKey depend : converter.convertTask(reader, taskKey).depends()) {
            if (!converted.containsKey(depend)) {
              queue.add(taskKey);
              taskReady = false;
              break;
            }
          }
          if (taskReady) {
            final ObjectId objectId = converter.convertTask(reader, taskKey).convert(dstRepo, inserter, converted::get, uploader);
            converted.put(taskKey, objectId);
            reporter.increment();
          }
        }
      }
    }
  }

  private static void removeDirectory(@NotNull Path path) throws IOException {
    if (Files.exists(path)) {
      Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
          Files.delete(file);
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
          Files.delete(dir);
          return FileVisitResult.CONTINUE;
        }
      });
    }
  }

  @NotNull
  private static Deque<TaskKey> processWithoutDependencies(@NotNull GitConverter converter, @NotNull Repository srcRepo, @NotNull Repository dstRepo, @NotNull ConcurrentMap<TaskKey, ObjectId> converted, @Nullable HttpUploader uploader, int threads) throws IOException, InterruptedException {
    AtomicLong total = new AtomicLong(0);
    try (ProgressReporter reporter = new ProgressReporter("processed", total, null)) {
      final Set<TaskKey> checked = new HashSet<>();
      final Deque<TaskKey> pass2 = new ArrayDeque<>();
      final Deque<TaskKey> queue = new ArrayDeque<>();
      // Heads
      for (Ref ref : srcRepo.getAllRefs().values()) {
        final TaskKey taskKey = new TaskKey(GitConverter.TaskType.Simple, "", ref.getObjectId());
        if (checked.add(taskKey)) {
          queue.add(taskKey);
        }
      }

      final ExecutorService pool = Executors.newFixedThreadPool(threads);
      try {
        final AtomicBoolean done = new AtomicBoolean(false);
        final List<Future<?>> jobs = new ArrayList<>(threads);
        final BlockingQueue<TaskKey> channel = new LinkedBlockingQueue<>();
        for (int i = 0; i < threads; ++i) {
          jobs.add(pool.submit(() -> {
            try {
              final ObjectInserter inserter = dstRepo.newObjectInserter();
              final ObjectReader reader = srcRepo.newObjectReader();
              while (!done.get()) {
                final TaskKey taskKey = channel.take();
                if (taskKey.getType() == GitConverter.TaskType.EndMark) break;
                final ObjectId objectId = converter.convertTask(reader, taskKey).convert(dstRepo, inserter, converted::get, uploader);
                converted.put(taskKey, objectId);
                reporter.increment();
              }
              inserter.flush();
            } catch (IOException | InterruptedException e) {
              rethrow(e);
            } finally {
              done.set(true);
            }
          }));
        }
        final ObjectReader reader = srcRepo.newObjectReader();
        while (!queue.isEmpty()) {
          final TaskKey taskKey = queue.pop();
          boolean withoutDepends = true;
          for (TaskKey depend : converter.convertTask(reader, taskKey).depends()) {
            withoutDepends = false;
            if (checked.add(depend)) {
              queue.add(depend);
            }
          }
          if (withoutDepends) {
            total.incrementAndGet();
            channel.add(taskKey);
          } else {
            pass2.add(taskKey);
          }
        }
        for (Future<?> ignored : jobs) {
          channel.add(new TaskKey(GitConverter.TaskType.EndMark, null, ObjectId.zeroId()));
        }
        for (Future<?> job : jobs) {
          try {
            job.get();
          } catch (ExecutionException e) {
            rethrow(e.getCause());
          }
        }
      } finally {
        pool.shutdown();
      }
      return pass2;
    }
  }

  public static void rethrow(final Throwable exception) {
    class EvilThrower<T extends Throwable> {
      @SuppressWarnings("unchecked")
      private void sneakyThrow(Throwable exception) throws T {
        throw (T) exception;
      }
    }
    new EvilThrower<RuntimeException>().sneakyThrow(exception);
  }

  public static class HttpUploader implements GitConverter.Uploader, AutoCloseable {
    @NotNull
    private final ThreadLocal<ObjectReader> readers = new ThreadLocal<>();
    @NotNull
    private final ExecutorService pool;
    @NotNull
    private final Repository repository;
    @NotNull
    private final BatchUploader uploader;
    @NotNull
    private final Collection<CompletableFuture<?>> futures = new LinkedBlockingQueue<>();
    @NotNull
    private final AtomicInteger finished = new AtomicInteger();
    @NotNull
    private final AtomicInteger total = new AtomicInteger();

    public HttpUploader(@NotNull Repository repository, @NotNull Client client, int threads) {
      this.pool = Executors.newFixedThreadPool(threads);
      this.uploader = new BatchUploader(client, pool);
      this.repository = repository;
    }

    @Override
    public void upload(@NotNull ObjectId oid, @NotNull Meta meta) {
      total.incrementAndGet();
      futures.add(uploader.upload(meta, () -> getReader().open(oid).openStream()).thenAccept((m) -> finished.incrementAndGet()));
    }

    @NotNull
    private ObjectReader getReader() {
      ObjectReader reader = readers.get();
      if (reader == null) {
        reader = repository.newObjectReader();
        readers.set(reader);
      }
      return reader;
    }

    @Override
    public void close() throws ExecutionException, InterruptedException {
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[futures.size()])).get();
      pool.shutdown();
    }

    public int getTotal() {
      return total.get();
    }

    public int getFinished() {
      return finished.get();
    }
  }

  public static class ProgressReporter implements AutoCloseable {
    private static final long DELAY = TimeUnit.SECONDS.toMillis(1);
    @Nullable
    private final AtomicLong total;
    @NotNull
    private final AtomicLong current = new AtomicLong(0);
    @NotNull
    private final AtomicLong lastTime = new AtomicLong(0);
    @NotNull
    private final String prefix;
    @Nullable
    private final HttpUploader uploader;

    public ProgressReporter(@NotNull String prefix, @Nullable AtomicLong total, @Nullable HttpUploader uploader) {
      this.prefix = prefix;
      this.total = total;
      this.uploader = uploader;
    }

    public void increment() {
      final long last = current.incrementAndGet();
      final long oldTime = lastTime.get();
      final long newTime = System.currentTimeMillis();
      if (oldTime < newTime - DELAY) {
        if (lastTime.compareAndSet(oldTime, newTime)) {
          print(last);
        }
      }
    }

    @Override
    public void close() {
      print(current.get());
    }

    private void print(long current) {
      String message = "  " + prefix + ": " + current + (total != null ? "/" + total.get() : "");
      if (uploader != null) {
        message += ", uploaded: " + uploader.getFinished() + "/" + uploader.getTotal();
      }
      log.info(message);
    }

  }

  public static class CmdArgs {
    @Parameter(names = {"-s", "--source"}, description = "Source repository", required = true)
    @NotNull
    private Path src;
    @Parameter(names = {"-d", "--destination"}, description = "Destination repository", required = true)
    @NotNull
    private Path dst;
    @Parameter(names = {"-c", "--cache"}, description = "Source repository", required = false)
    @NotNull
    private Path cache = FileSystems.getDefault().getPath(".");
    @Parameter(names = {"-g", "--git"}, description = "GIT repository url (ignored with --lfs parameter)", required = false)
    @Nullable
    private String git;
    @Parameter(names = {"-l", "--lfs"}, description = "LFS server url (can be determinated by --git paramter)", required = false)
    @Nullable
    private String lfs;
    @Parameter(names = {"-t", "--write-threads"}, description = "IO thread count", required = false)
    private int writeThreads = 2;
    @Parameter(names = {"-u", "--upload-threads"}, description = "HTTP upload thread count", required = false)
    private int uploadThreads = 4;
    @Parameter(names = {"--check-lfs"}, description = "Check LFS server settings and exit")
    private boolean checkLfs = false;
    @Parameter(names = {"--no-check-certificate"}, description = "Don't check the server certificate against the available certificate authorities")
    private boolean noCheckCertificate = false;
    @Parameter(names = {"--glob-file"}, description = "File containing glob patterns")
    private Path globFile = null;

    @Parameter(description = "LFS file glob patterns")
    @NotNull
    private List<String> globs = new ArrayList<>();
    @Parameter(names = {"-h", "--help"}, description = "Show help", help = true)
    private boolean help = false;
  }
}
