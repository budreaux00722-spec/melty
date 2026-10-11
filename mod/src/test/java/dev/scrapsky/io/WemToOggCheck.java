package dev.scrapsky.io;

import java.nio.file.Files;
import java.nio.file.Path;

/** Converts a .wem with the bundled codebooks: WemToOggCheck in.wem codebooks.bin out.ogg */
public final class WemToOggCheck {
    public static void main(String[] a) throws Exception {
        var books = new WemToOgg.CodebookLibrary(Files.readAllBytes(Path.of(a[1])));
        long t = System.nanoTime();
        var r = WemToOgg.convert(Files.readAllBytes(Path.of(a[0])), books);
        Files.write(Path.of(a[2]), r.ogg());
        System.out.printf("ok %d ch %d Hz %.1f s in %d ms%n", r.channels(), r.sampleRate(), r.seconds(), (System.nanoTime() - t) / 1_000_000);
    }
}
