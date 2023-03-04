
/**
 * @version 0.0.6
 * @author Demod
 */

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.DeflaterOutputStream;

import javax.imageio.ImageIO;
import javax.swing.JOptionPane;

public class ZPacker {
    // Usage: java ZPack.java <folder>
    public static void main(String[] args) {
        try {
            File file = new File(args[0]);

            List<FileProcessor> processors = new ArrayList<>();
            processors.add(FileProcessor.THUMBY_IMAGE);
            processors.add(FileProcessor.PYTHON);
            processors.add(FileProcessor.ANY);

            Map<String, ManifestEntry> manifest = new HashMap<>();
            if (file.isFile()) {
                populateManifest(manifest, processors, "", file);
            } else if (file.isDirectory()) {
                for (File subFile : file.listFiles()) {
                    populateManifest(manifest, processors, "", subFile);
                }
            }

            List<String> manifestOrder = manifest.keySet().stream().sorted().collect(Collectors.toList());

            System.out.print("Writing Pack File... ");

            File packFile = new File(file.getParentFile(), file.getName() + ".pack");
            int lastOffset;
            try (FileOutputStream fos = new FileOutputStream(packFile);
                    DataOutputStream dos = new DataOutputStream(fos)) {
                FileChannel fc = fos.getChannel();

                int manifestSizeOffset = (int) fc.position();
                dos.writeInt(0);// Manifest Size (placeholder)
                dos.writeByte(manifest.size());
                for (String key : manifestOrder) {
                    ManifestEntry entry = manifest.get(key);
                    encodeUTF8(dos, key);
                    dos.writeByte(entry.metaId.code);
                    dos.writeByte(entry.data.length);
                    for (byte[] data : entry.data) {
                        dos.writeInt(data.length);
                    }
                    dos.write(entry.metadata);
                }
                lastOffset = (int) fc.position();
                fc.position(manifestSizeOffset);
                dos.writeInt(lastOffset - manifestSizeOffset - 4);// Manifest Size
                fc.position(lastOffset);

                for (int i = 0; i < manifestOrder.size(); i++) {
                    String key = manifestOrder.get(i);
                    ManifestEntry entry = manifest.get(key);
                    for (byte[] data : entry.data) {
                        dos.write(data);
                    }
                }

                lastOffset = (int) fc.position();
            }
            int rawSize = lastOffset;
            System.out.println(rawSize + " bytes");

            System.out.print("Compressing Pack File... ");

            File zpackFile = new File(file.getParentFile(), file.getName() + ".zpack");
            byte[] packBytes = Files.readAllBytes(packFile.toPath());
            try (FileOutputStream fos = new FileOutputStream(zpackFile);
                    DeflaterOutputStream zos = new DeflaterOutputStream(fos)) {
                zos.write(packBytes);
                lastOffset = (int) fos.getChannel().position();
            }
            packFile.delete();
            Thread.sleep(1000);
            int compSize = (int) zpackFile.length();

            System.out.println(compSize + " bytes ("
                    + (int) ((100 * (rawSize - compSize)) / rawSize) + "%)");

            System.out.println("Saved to " + zpackFile.getAbsolutePath());

        } catch (Exception e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(null, "Error: " + e.getMessage());
        }
    }

    public static void populateManifest(Map<String, ManifestEntry> manifest, List<FileProcessor> processors,
            String prefix,
            File file) {
        if (file.isFile()) {
            ManifestEntry entry = new ManifestEntry();
            entry.key = prefix + getNameWithoutExtension(file.getName());
            entry.file = file;
            for (FileProcessor processor : processors) {
                try {
                    if (processor.fileAccept.accept(file)) {
                        System.out.print(entry.key);
                        processor.processor.accept(entry);
                        System.out.println(" (" + entry.metaId.name() + ") "
                                + Arrays.stream(entry.data).mapToInt(d -> d.length).sum() + " bytes");
                        manifest.put(entry.key, entry);
                        break;
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    JOptionPane.showMessageDialog(null, entry.key + " Error: " + e.getMessage());
                }
            }
        } else if (file.isDirectory()) {
            for (File subFile : file.listFiles()) {
                populateManifest(manifest, processors, prefix + file.getName() + "/", subFile);
            }
        }
    }

    public enum FileProcessor {
        THUMBY_IMAGE(FP::acceptAllImages, FP::processThumbyImage),
        PYTHON(f -> f.getName().endsWith(".py"), FP::processPython),
        ANY(f -> true, e -> e.data = new byte[][] { Files.readAllBytes(e.file.toPath()) }),
        ;

        private UnsafePredicate<File, IOException> fileAccept;
        private UnsafeConsumer<ManifestEntry, IOException> processor;

        private FileProcessor(UnsafePredicate<File, IOException> fileAccept,
                UnsafeConsumer<ManifestEntry, IOException> processor) {
            this.fileAccept = fileAccept;
            this.processor = processor;
        }
    }

    public static final int META_FLAG_IMAGE = 0x10;

    public enum MetaId {
        RAW(0),
        PYTHON(1),
        IMAGE(2),
        ;

        private int code;

        private MetaId(int code) {
            this.code = code;
        }
    }

    public static class FP {
        private static final String[] imageExts = { ".jpeg", ".jpg", ".png", ".bmp", ".gif" };

        public static boolean acceptAllImages(File file) {
            return Arrays.stream(imageExts).anyMatch(ext -> file.getName().toLowerCase().endsWith(ext));
        }

        public static void processThumbyImage(ManifestEntry entry) throws IOException {
            Optional<FrameParams> frameParams = extractFrameParams(entry.file.getName());
            boolean frames = frameParams.isPresent();
            int width, height;

            BufferedImage source = ImageIO.read(entry.file);
            List<BufferedImage> images;
            if (frames) {
                FrameParams fp = frameParams.get();
                entry.key = fp.key;
                width = fp.width;
                height = fp.height;
                images = cutFrames(source, width, height);
            } else {
                width = source.getWidth();
                height = source.getHeight();
                images = Arrays.asList(source);
            }

            try (ByteArrayOutputStream baos1 = new ByteArrayOutputStream();
                    ByteArrayOutputStream baos2 = new ByteArrayOutputStream();
                    ByteArrayOutputStream baosMask = new ByteArrayOutputStream()) {
                boolean grayed = false;
                boolean masked = false;
                for (BufferedImage image : images) {
                    for (int y = 0; y < image.getHeight(); y += 8) {
                        for (int x = 0; x < image.getWidth(); x++) {
                            int value1 = 0;
                            int value2 = 0;
                            int valueMask = 0;
                            for (int j = 7; j >= 0; j--) {
                                Color color = (y + j < image.getHeight()) ? new Color(image.getRGB(x, y + j), true)
                                        : Color.black;
                                int grayAvg = (color.getRed() + color.getGreen() + color.getBlue()) / 3;
                                boolean alpha = color.getAlpha() >= 128;
                                int gray2Bit;
                                if (alpha) {
                                    gray2Bit = Math.round(grayAvg / (256 / 3f));
                                } else {
                                    gray2Bit = 0;
                                    masked = true;
                                }
                                boolean b1 = gray2Bit == 2 || gray2Bit == 3;
                                boolean b2 = gray2Bit == 1 || gray2Bit == 2;
                                if (b2) {
                                    grayed = true;
                                }
                                value1 <<= 1;
                                value2 <<= 1;
                                valueMask <<= 1;
                                if (b1) {
                                    value1 |= 1;
                                }
                                if (b2) {
                                    value2 |= 1;
                                }
                                if (alpha) {
                                    valueMask |= 1;
                                }
                            }
                            baos1.write(value1);
                            baos2.write(value2);
                            baosMask.write(valueMask);
                        }
                    }
                }
                baos1.flush();
                baos2.flush();
                baosMask.flush();

                System.out.print(" " + (frames ? "F" : "") + (grayed ? "G" : "") + (masked ? "M" : "")
                        + (frames ? (" x" + images.size()) : ""));

                try (ByteArrayOutputStream baosMeta = new ByteArrayOutputStream();
                        DataOutputStream dos = new DataOutputStream(baosMeta)) {
                    int flags = 0;
                    if (masked) {
                        flags |= 0x01;
                    }
                    if (grayed) {
                        flags |= 0x02;
                    }
                    if (frames) {
                        flags |= 0x04;
                    }
                    dos.writeByte(flags);
                    dos.writeShort(width);
                    dos.writeShort(height);
                    if (frames) {
                        dos.writeByte(images.size()); // TODO check if over 255
                    }
                    dos.flush();
                    entry.metadata = baosMeta.toByteArray();
                }

                entry.metaId = MetaId.IMAGE;
                if (!masked) {
                    if (!grayed) {
                        entry.data = new byte[][] {
                                baos1.toByteArray()
                        };
                    } else {
                        entry.data = new byte[][] {
                                baos1.toByteArray(),
                                baos2.toByteArray()
                        };
                    }
                } else {
                    if (!grayed) {
                        entry.data = new byte[][] {
                                baos1.toByteArray(),
                                baosMask.toByteArray()
                        };
                    } else {
                        entry.data = new byte[][] {
                                baos1.toByteArray(),
                                baos2.toByteArray(),
                                baosMask.toByteArray()
                        };
                    }
                }
            }
        }

        private static class FrameParams {
            private String key;
            private int width;
            private int height;
        }

        private static Optional<FrameParams> extractFrameParams(String filename) {
            Pattern pattern = Pattern.compile("\\s(\\d+)x(\\d+)\\.[^.]+$");
            Matcher matcher = pattern.matcher(filename);
            if (matcher.find()) {
                FrameParams ret = new FrameParams();
                ret.key = pattern.split(filename)[0];
                ret.width = Integer.parseInt(matcher.group(1));
                ret.height = Integer.parseInt(matcher.group(2));
                return Optional.of(ret);
            }
            return Optional.empty();
        }

        private static List<BufferedImage> cutFrames(BufferedImage image, int width, int height) {
            List<BufferedImage> ret = new ArrayList<>();
            int columns = (image.getWidth() + width - 1) / width;
            int rows = (image.getHeight() + height - 1) / height;
            for (int row = 0; row < rows; row++) {
                for (int col = 0; col < columns; col++) {
                    BufferedImage frame = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D g = frame.createGraphics();
                    g.drawImage(image, 0, 0, width, height,
                            col * width, row * height, (col + 1) * width, (row + 1) * height, null);
                    g.dispose();
                    ret.add(frame);
                }
            }
            return ret;
        }

        public static void processPython(ManifestEntry entry) throws IOException {
            entry.metaId = MetaId.PYTHON;
            try (ByteArrayOutputStream baosMeta = new ByteArrayOutputStream();
                    DataOutputStream dos = new DataOutputStream(baosMeta)) {
                encodeUTF8(dos, getNameWithoutExtension(entry.key));
                dos.flush();
                entry.metadata = baosMeta.toByteArray();
            }
            entry.data = new byte[][] { Files.readAllBytes(entry.file.toPath()) };
        }
    }

    @FunctionalInterface
    public interface UnsafePredicate<T, E extends Exception> {
        public boolean accept(T t) throws E;
    }

    @FunctionalInterface
    public interface UnsafeConsumer<T, E extends Exception> {
        public void accept(T t) throws E;
    }

    public static class ManifestEntry {
        public String key;
        public File file;
        public MetaId metaId = MetaId.RAW;
        public byte[] metadata = new byte[0];
        public byte[][] data;
    }

    public static String getNameWithoutExtension(String filePath) {
        File file = new File(filePath);
        String fileName = file.getName();
        int index = fileName.lastIndexOf(".");
        return index > 0 ? fileName.substring(0, index) : fileName;
    }

    public static void encodeUTF8(DataOutputStream dos, String string) throws IOException {
        byte[] bytes = string.getBytes(StandardCharsets.UTF_8);
        dos.writeShort(bytes.length);
        dos.write(bytes);
    }
}
