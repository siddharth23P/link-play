import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Draws the Link Play launcher icon with Java2D and writes every icon file the desktop app needs.
 *
 * The picture is the phone app's launcher icon (android/app/src/main/res/drawable/ic_launcher.xml,
 * 108 x 108 units): a dark tile, a purple handheld body, a cut-out D-pad, a green A and a pink B.
 * Everything is drawn from that geometry at each size (nothing is scaled down from a big bitmap),
 * so small sizes stay as crisp as the vector allows.
 *
 * Run through make-icons.sh (needs only a JDK 11+):  java IconGen.java <desktop dir> <iconset dir>
 *
 * Writes:
 *   <desktop>/src/main/resources/icons/linkplay-{32,64,128,256,512}.png   window / taskbar icons
 *   <desktop>/packaging/windows/LinkPlay.ico                               installer + exe icon
 *   <iconset dir>/icon_*.png                                               input for macOS iconutil
 */
public class IconGen {
	static final Color INK_DARK = new Color(0x14121C);
	static final Color BODY = new Color(0x7C5CFF);
	static final Color BTN_A = new Color(0x4AD6A6);
	static final Color BTN_B = new Color(0xFF6B81);

	/** The handheld on a transparent canvas, in the 108-unit space of the Android vector. */
	static void glyphs(Graphics2D g) {
		g.setColor(BODY);
		g.fill(new RoundRectangle2D.Double(22, 34, 64, 40, 16, 16));
		g.setColor(INK_DARK);
		g.fill(new Rectangle2D.Double(38, 48, 12, 4));
		g.fill(new Rectangle2D.Double(42, 44, 4, 12));
		g.setColor(BTN_A);
		g.fill(new Ellipse2D.Double(66, 46, 8, 8));
		g.setColor(BTN_B);
		g.fill(new Ellipse2D.Double(58, 54, 8, 8));
	}

	static Graphics2D open(BufferedImage img) {
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		return g;
	}

	/**
	 * Window / Windows icon: the full-bleed 108-unit tile with softly rounded corners, no margin, so it
	 * stays readable at 16 to 32 px in a title bar or taskbar.
	 */
	static BufferedImage tile(int size) {
		BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = open(img);
		g.scale(size / 108.0, size / 108.0);
		g.setColor(INK_DARK);
		g.fill(new RoundRectangle2D.Double(0, 0, 108, 108, 24, 24));
		glyphs(g);
		g.dispose();
		return img;
	}

	/**
	 * macOS icon: 1024 canvas with a 100 px transparent margin and an 824 px rounded square
	 * (radius ~185), the shape macOS expects. Drawn directly at the target size.
	 */
	static BufferedImage macTile(int size) {
		BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = open(img);
		double k = size / 1024.0;
		g.scale(k, k);
		g.setColor(INK_DARK);
		g.fill(new RoundRectangle2D.Double(100, 100, 824, 824, 370, 370));
		g.translate(100, 100);
		double s = 824.0 / 108.0;
		g.scale(s, s);
		glyphs(g);
		g.dispose();
		return img;
	}

	static byte[] png(BufferedImage img) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		if (!ImageIO.write(img, "png", out)) throw new IOException("no PNG writer");
		return out.toByteArray();
	}

	static void writePng(BufferedImage img, Path file) throws IOException {
		Files.createDirectories(file.getParent());
		Files.write(file, png(img));
	}

	/** One 32-bit BMP image for an ICO entry (BITMAPINFOHEADER with doubled height, then pixels, then an unused AND mask). */
	static byte[] dib(BufferedImage img) {
		int w = img.getWidth(), h = img.getHeight();
		int maskRow = ((w + 31) / 32) * 4;
		ByteBuffer b = ByteBuffer.allocate(40 + w * h * 4 + maskRow * h).order(ByteOrder.LITTLE_ENDIAN);
		b.putInt(40).putInt(w).putInt(h * 2).putShort((short) 1).putShort((short) 32);
		b.putInt(0).putInt(w * h * 4).putInt(0).putInt(0).putInt(0).putInt(0);
		for (int y = h - 1; y >= 0; y--) { // bottom-up rows, BGRA
			for (int x = 0; x < w; x++) b.putInt(img.getRGB(x, y)); // ARGB int, little endian = B G R A
		}
		return b.array(); // AND mask stays zero: alpha channel decides transparency
	}

	/**
	 * Writes an ICO. Sizes below 256 use plain BMP entries (understood by every Windows tool, including
	 * jpackage's resource editor); 256 uses a PNG entry, as Windows Vista and later expect.
	 */
	static void writeIco(int[] sizes, Path file) throws IOException {
		List<byte[]> images = new ArrayList<>();
		for (int s : sizes) images.add(s >= 256 ? png(tile(s)) : dib(tile(s)));
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		ByteBuffer head = ByteBuffer.allocate(6 + 16 * sizes.length).order(ByteOrder.LITTLE_ENDIAN);
		head.putShort((short) 0).putShort((short) 1).putShort((short) sizes.length);
		int offset = head.capacity();
		for (int i = 0; i < sizes.length; i++) {
			int s = sizes[i];
			head.put((byte) (s >= 256 ? 0 : s)).put((byte) (s >= 256 ? 0 : s)).put((byte) 0).put((byte) 0);
			head.putShort((short) 1).putShort((short) 32).putInt(images.get(i).length).putInt(offset);
			offset += images.get(i).length;
		}
		bos.write(head.array());
		for (byte[] im : images) bos.write(im);
		Files.createDirectories(file.getParent());
		Files.write(file, bos.toByteArray());
	}

	public static void main(String[] args) throws Exception {
		if (args.length < 2) {
			System.err.println("usage: java IconGen.java <desktop dir> <iconset dir>");
			System.exit(2);
		}
		Path desktop = new File(args[0]).toPath();
		Path iconset = new File(args[1]).toPath();
		System.setProperty("java.awt.headless", "true");

		for (int s : new int[] {32, 64, 128, 256, 512}) {
			writePng(tile(s), desktop.resolve("src/main/resources/icons/linkplay-" + s + ".png"));
		}
		writeIco(new int[] {16, 24, 32, 48, 64, 128, 256}, desktop.resolve("packaging/windows/LinkPlay.ico"));

		// iconutil wants exactly these names: icon_NxN.png and icon_NxN@2x.png.
		int[] points = {16, 32, 128, 256, 512};
		for (int p : points) {
			writePng(macTile(p), iconset.resolve("icon_" + p + "x" + p + ".png"));
			writePng(macTile(p * 2), iconset.resolve("icon_" + p + "x" + p + "@2x.png"));
		}
		// A big preview next to the iconset, for looking at.
		writePng(macTile(1024), iconset.resolve("../preview-mac-1024.png").normalize());
		System.out.println("icons written");
	}
}
