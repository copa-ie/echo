package simplesound.pcm;

import static org.jcaki.Bytes.toByteArray;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

public class PcmAudioHelper {

    /**
     * Modifies the size information in a wav file header.
     *
     * @param wavFile a wav file
     * @param size    size to replace the header.
     * @throws IOException if an error occurs while accessing the data.
     */
    static void modifyRiffSizeData(File wavFile, int size) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(wavFile, "rw");
        try {
            raf.seek(RiffHeaderData.RIFF_CHUNK_SIZE_INDEX);
            raf.write(toByteArray(size + 36, false));
            raf.seek(RiffHeaderData.RIFF_SUBCHUNK2_SIZE_INDEX);
            raf.write(toByteArray(size, false));
        } finally {
            raf.close();
        }
    }
}
