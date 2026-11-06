package com.lowdragmc.minebox3d.natives;

import com.lowdragmc.minebox3d.internal.B3;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * <b>Loads the Box3D library</b> once per process.
 *
 * <p>Where it comes from, first match wins:
 * <ol>
 *   <li>the system property {@code minebox3d.native.path}: a file on disk. Dev runs and tests point
 *       it at the library the Gradle build just compiled.</li>
 *   <li>the jar, at {@code natives/minebox3d/<os>-<arch>/<file>}, copied out to a folder named by the
 *       file's own hash under the system temp directory and loaded from there.</li>
 * </ol>
 *
 * <p>⚠️ Two things about Minecraft's class loading decide how this is written:
 * <ul>
 *   <li>Mods are named JPMS modules, and {@code Class.getResourceAsStream} does not expose a module's
 *       non-class resources. {@code ClassLoader.getResourceAsStream} does, so that is what is used.</li>
 *   <li>JNI binds a native method to the library loaded by the class loader of the class that
 *       declares it. {@link B3} and this class are in the same mod and so the same loader; calling
 *       {@link System#load} from here is what makes {@code B3}'s methods resolve.</li>
 * </ul>
 *
 * <p>A copy is never overwritten: the folder name is the hash of its content, so a file that is
 * there is the right file — and on Windows a library some other running game has loaded could not be
 * replaced anyway.
 */
public final class NativeLibrary {

    public static final String PATH_PROPERTY = "minebox3d.native.path";

    private static volatile boolean loaded;
    @Nullable
    private static volatile Throwable failure;
    @Nullable
    private static volatile Path loadedFrom;
    private static volatile boolean fromJar;

    private NativeLibrary() {
    }

    /** Loads the library if that has not happened. Throws what loading threw, every time it is asked. */
    public static synchronized void load() {
        if (loaded) {
            return;
        }
        if (failure != null) {
            throw new IllegalStateException("The MineBox3D native library failed to load", failure);
        }
        try {
            Path file = locate();
            System.load(file.toAbsolutePath().toString());
            loadedFrom = file;
            if (!B3.init(B3.boundClasses())) {
                throw new IllegalStateException("B3.init returned false");
            }
            if (!B3.isDoublePrecision()) {
                throw new IllegalStateException("the native library was not built with BOX3D_DOUBLE_PRECISION");
            }
            loaded = true;
        } catch (Throwable t) {
            failure = t;
            throw new IllegalStateException("The MineBox3D native library failed to load", t);
        }
    }

    /** Loads the library if it can; {@code false} (and {@link #failure()} says why) if it cannot. */
    public static boolean tryLoad() {
        try {
            load();
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    public static boolean isLoaded() {
        return loaded;
    }

    @Nullable
    public static Throwable failure() {
        return failure;
    }

    /** The file that was loaded, or {@code null} before a successful load. */
    @Nullable
    public static Path loadedFrom() {
        return loadedFrom;
    }

    /** Whether the library came out of the mod's own resources rather than {@link #PATH_PROPERTY}. */
    public static boolean loadedFromJar() {
        return fromJar;
    }

    /** {@code <os>-<arch>} as the jar names its folders: {@code windows-x86_64}, {@code macos-aarch64}, … */
    public static String platform() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        String osName = os.contains("win") ? "windows" : os.contains("mac") || os.contains("darwin") ? "macos" : "linux";
        String archName = switch (arch) {
            case "amd64", "x86_64", "x64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> arch;
        };
        return osName + "-" + archName;
    }

    public static String fileName() {
        String platform = platform();
        if (platform.startsWith("windows")) {
            return "minebox3d.dll";
        }
        return platform.startsWith("macos") ? "libminebox3d.dylib" : "libminebox3d.so";
    }

    private static Path locate() throws IOException {
        String override = System.getProperty(PATH_PROPERTY);
        if (override != null && !override.isBlank()) {
            Path path = Path.of(override);
            if (!Files.isRegularFile(path)) {
                throw new IOException(PATH_PROPERTY + " points at " + path + ", which is not a file");
            }
            return path;
        }
        fromJar = true;
        String resource = "natives/minebox3d/" + platform() + "/" + fileName();
        ClassLoader loader = NativeLibrary.class.getClassLoader();
        byte[] bytes;
        try (InputStream in = loader.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("this build of MineBox3D has no native library for " + platform()
                        + " (looked for " + resource + ")");
            }
            bytes = in.readAllBytes();
        }
        Path folder = Path.of(System.getProperty("java.io.tmpdir"), "minebox3d", hash(bytes));
        Path file = folder.resolve(fileName());
        if (!Files.isRegularFile(file) || Files.size(file) != bytes.length) {
            Files.createDirectories(folder);
            Path partial = Files.createTempFile(folder, "partial", ".tmp");
            Files.write(partial, bytes);
            try {
                Files.move(partial, file, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                // another game copied the same file out at the same moment; theirs is identical
                Files.deleteIfExists(partial);
                if (!Files.isRegularFile(file)) {
                    throw e;
                }
            }
        }
        return file;
    }

    private static String hash(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
