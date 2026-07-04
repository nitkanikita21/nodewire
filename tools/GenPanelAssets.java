import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import javax.imageio.ImageIO;

/**
 * Procedural Control Panel asset generator — item icons (32×32 bevelled body +
 * per-type glyph), the panel plate texture and the matching item-model JSONs.
 * No hand-authored art; output is committed. Run from the repo root:
 *
 *   java tools/GenPanelAssets.java          (or ./gradlew genPanelAssets)
 */
public final class GenPanelAssets {

    static final int BODY = 0xFF2A2D31, BEVEL_HI = 0xFF4A4F55, BEVEL_LO = 0xFF17191C;

    public static void main(String[] args) throws IOException {
        File assets = new File("src/main/resources/assets/nodewire");
        if (!assets.isDirectory()) {
            System.err.println("run from the repo root (missing " + assets + ")");
            System.exit(1);
        }
        File texItem = mkdirs(new File(assets, "textures/item"));
        File texBlock = mkdirs(new File(assets, "textures/block"));
        File modItem = mkdirs(new File(assets, "models/item"));

        Map<String, Consumer<Graphics2D>> glyphs = new LinkedHashMap<>();
        glyphs.put("toggle", g -> {
            g.setColor(c(0xFF17191C)); g.fillRect(11, 6, 10, 20);
            g.setColor(c(0xFF3FD24A)); g.fillRect(12, 7, 8, 9);
        });
        glyphs.put("momentary", g -> {
            g.setColor(c(0xFF17191C)); g.fillOval(8, 8, 16, 16);
            g.setColor(c(0xFF5C7CE8)); g.fillOval(10, 10, 12, 12);
        });
        glyphs.put("selector", g -> {
            g.setColor(c(0xFF17191C)); g.fillRect(6, 12, 20, 8);
            g.setColor(c(0xFFE8C85C)); g.fillRect(7, 13, 6, 6);
            g.setColor(c(0xFF4A4F55)); g.fillRect(14, 13, 5, 6); g.fillRect(20, 13, 5, 6);
        });
        glyphs.put("slider", g -> {
            g.setColor(c(0xFF17191C)); g.fillRect(5, 14, 22, 4);
            g.setColor(c(0xFFCCCCCC)); g.fillRect(17, 9, 4, 14);
        });
        glyphs.put("knob", g -> {
            g.setColor(c(0xFF17191C)); g.fillOval(7, 7, 18, 18);
            g.setColor(c(0xFF4A4F55)); g.fillOval(9, 9, 14, 14);
            g.setColor(c(0xFFE8A23A)); g.fillRect(15, 8, 2, 8);
        });
        glyphs.put("lamp", g -> {
            g.setColor(c(0xFF551111)); g.fillOval(8, 8, 16, 16);
            g.setColor(c(0xFFFF3333)); g.fillOval(10, 10, 12, 12);
            g.setColor(c(0xFFFF8A8A)); g.fillOval(13, 12, 4, 4);
        });
        glyphs.put("bar", g -> {
            g.setColor(c(0xFF17191C)); g.fillRect(6, 6, 20, 20);
            g.setColor(c(0xFF33CCCC));
            g.fillRect(8, 20, 3, 4); g.fillRect(13, 16, 3, 8); g.fillRect(18, 12, 3, 12);
            g.setColor(c(0xFF4A4F55)); g.fillRect(23, 8, 3, 16);
        });
        glyphs.put("numeric", g -> {
            g.setColor(c(0xFF050505)); g.fillRect(5, 9, 22, 14);
            g.setColor(c(0xFF3FD24A));
            g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 12));
            g.drawString("88", 9, 20);
        });
        glyphs.put("screen", g -> {
            g.setColor(c(0xFF050505)); g.fillRect(5, 7, 22, 18);
            g.setColor(c(0xFF15304A)); g.fillRect(6, 8, 20, 16);
            g.setColor(c(0xFF2A5A8A)); g.fillRect(6, 12, 20, 2);
        });
        glyphs.put("label", g -> {
            g.setColor(c(0xFFB8BEC4)); g.fillRect(7, 11, 18, 3); g.fillRect(7, 18, 12, 3);
        });

        for (var e : glyphs.entrySet()) {
            ImageIO.write(bodyIcon(e.getValue()), "png", new File(texItem, "panel_" + e.getKey() + ".png"));
        }

        // Panel Key: golden service key on the same body plate.
        ImageIO.write(bodyIcon(g -> {
            g.setColor(c(0xFFE8C85C));
            g.fillOval(6, 6, 10, 10);
            g.setColor(c(0xFF2A2D31)); g.fillOval(9, 9, 4, 4);
            g.setColor(c(0xFFE8C85C));
            g.fillRect(14, 10, 12, 3); g.fillRect(20, 13, 2, 4); g.fillRect(24, 13, 2, 5);
        }), "png", new File(texItem, "panel_key.png"));

        // Plate texture (block particle + block item icon): dark plate + grid dots.
        BufferedImage plate = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = plate.createGraphics();
        g.setColor(c(0xFF202225)); g.fillRect(0, 0, 32, 32);
        g.setColor(c(0xFF33373C));
        for (int x = 0; x < 4; x++) for (int y = 0; y < 4; y++) g.fillRect(3 + x * 8, 3 + y * 8, 2, 2);
        g.setColor(c(0xFF4A4F55)); g.fillRect(0, 0, 32, 1); g.fillRect(0, 0, 1, 32);
        g.setColor(c(0xFF17191C)); g.fillRect(0, 31, 32, 1); g.fillRect(31, 0, 1, 32);
        g.dispose();
        ImageIO.write(plate, "png", new File(texBlock, "control_panel.png"));

        // Item models: flat generated icons over the emitted textures.
        for (String id : glyphs.keySet()) {
            writeJson(new File(modItem, "panel_" + id + ".json"),
                "{ \"parent\": \"minecraft:item/generated\", \"textures\": { \"layer0\": \"nodewire:item/panel_" + id + "\" } }");
        }
        writeJson(new File(modItem, "panel_key.json"),
            "{ \"parent\": \"minecraft:item/generated\", \"textures\": { \"layer0\": \"nodewire:item/panel_key\" } }");
        writeJson(new File(modItem, "control_panel.json"),
            "{ \"parent\": \"minecraft:item/generated\", \"textures\": { \"layer0\": \"nodewire:block/control_panel\" } }");
        // Block model stays invisible in-world (the BER draws the plate); the
        // generated plate texture is only its particle sprite.
        writeJson(new File(assets, "models/block/control_panel.json"),
            "{ \"textures\": { \"particle\": \"nodewire:block/control_panel\" } }");

        System.out.println("genPanelAssets: wrote " + (glyphs.size() + 1) + " item icons + plate texture + models");
    }

    /** 32×32 icon: bevelled dark body plate + the element glyph. */
    static BufferedImage bodyIcon(Consumer<Graphics2D> glyph) {
        BufferedImage im = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = im.createGraphics();
        g.setColor(c(BODY)); g.fillRect(2, 2, 28, 28);
        g.setColor(c(BEVEL_HI)); g.fillRect(2, 2, 28, 1); g.fillRect(2, 2, 1, 28);
        g.setColor(c(BEVEL_LO)); g.fillRect(2, 29, 28, 1); g.fillRect(29, 2, 1, 28);
        glyph.accept(g);
        g.dispose();
        return im;
    }

    static Color c(int argb) { return new Color(argb, true); }

    static File mkdirs(File f) { f.mkdirs(); return f; }

    static void writeJson(File f, String json) throws IOException {
        Files.writeString(f.toPath(), json + "\n");
    }

    private GenPanelAssets() {}
}
