import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Random;

/**
 * Procedurally paints Aura TV's original wallpapers, launcher icon and TV banner.
 * Everything here is generated from math — no third-party imagery — so the art is ours to ship.
 *
 * Usage: java tools/AuraArt.java <app/src/main directory>
 */
public class AuraArt {
    static final int W = 1920, H = 1080;

    public static void main(String[] args) throws Exception {
        File main = new File(args.length > 0 ? args[0] : "app/src/main");
        File wp = new File(main, "assets/wallpapers");
        wp.mkdirs();
        long t = System.currentTimeMillis();
        save(lake(), new File(wp, "lake.jpg"));
        save(aurora(), new File(wp, "aurora.jpg"));
        save(alpine(), new File(wp, "alpine.jpg"));
        save(dunes(), new File(wp, "dunes.jpg"));
        save(ocean(), new File(wp, "ocean.jpg"));
        save(nebula(), new File(wp, "nebula.jpg"));
        save(forest(), new File(wp, "forest.jpg"));
        save(silk(), new File(wp, "silk.jpg"));
        int[][] icons = {{48, 0}, {72, 1}, {96, 2}, {144, 3}, {192, 4}};
        String[] dirs = {"mipmap-mdpi", "mipmap-hdpi", "mipmap-xhdpi", "mipmap-xxhdpi", "mipmap-xxxhdpi"};
        for (int[] ic : icons) {
            File d = new File(main, "res/" + dirs[ic[1]]);
            d.mkdirs();
            ImageIO.write(icon(ic[0]), "png", new File(d, "ic_launcher.png"));
        }
        File bd = new File(main, "res/drawable-xhdpi");
        bd.mkdirs();
        ImageIO.write(banner(320, 180), "png", new File(bd, "banner.png"));
        System.out.println("art generated in " + (System.currentTimeMillis() - t) + " ms");
    }

    // ------------------------------------------------------------------ noise

    static int[] perm = new int[512];
    static {
        Random r = new Random(7);
        int[] p = new int[256];
        for (int i = 0; i < 256; i++) p[i] = i;
        for (int i = 255; i > 0; i--) { int j = r.nextInt(i + 1); int s = p[i]; p[i] = p[j]; p[j] = s; }
        for (int i = 0; i < 512; i++) perm[i] = p[i & 255];
    }
    static double fade(double t) { return t * t * t * (t * (t * 6 - 15) + 10); }
    static double grad(int h, double x, double y) {
        switch (h & 7) {
            case 0: return x + y; case 1: return -x + y; case 2: return x - y; case 3: return -x - y;
            case 4: return x; case 5: return -x; case 6: return y; default: return -y;
        }
    }
    /** Perlin noise, roughly -1..1. */
    static double noise(double x, double y) {
        int xi = (int) Math.floor(x) & 255, yi = (int) Math.floor(y) & 255;
        double xf = x - Math.floor(x), yf = y - Math.floor(y);
        double u = fade(xf), v = fade(yf);
        int aa = perm[perm[xi] + yi], ab = perm[perm[xi] + yi + 1], ba = perm[perm[xi + 1] + yi], bb = perm[perm[xi + 1] + yi + 1];
        double x1 = lerp(grad(aa, xf, yf), grad(ba, xf - 1, yf), u);
        double x2 = lerp(grad(ab, xf, yf - 1), grad(bb, xf - 1, yf - 1), u);
        return lerp(x1, x2, v);
    }
    static double fbm(double x, double y, int oct) {
        double s = 0, a = 0.5, f = 1, n = 0;
        for (int i = 0; i < oct; i++) { s += a * noise(x * f, y * f); n += a; a *= 0.5; f *= 2.03; }
        return s / n;
    }
    static double ridged(double x, double y) {
        double s = 0, a = 0.5, f = 1;
        for (int i = 0; i < 5; i++) { s += a * (1 - Math.abs(noise(x * f, y * f))); a *= 0.5; f *= 2.05; }
        return s;
    }
    static double lerp(double a, double b, double t) { return a + (b - a) * t; }
    static double clamp(double v) { return v < 0 ? 0 : v > 1 ? 1 : v; }
    static double smooth(double e0, double e1, double x) { double t = clamp((x - e0) / (e1 - e0)); return t * t * (3 - 2 * t); }

    // ------------------------------------------------------------------ canvas (float RGB)

    static class Img {
        final int w, h; final float[] r, g, b;
        Img(int w, int h) { this.w = w; this.h = h; r = new float[w * h]; g = new float[w * h]; b = new float[w * h]; }
        void set(int x, int y, double[] c) { int i = y * w + x; r[i] = (float) c[0]; g[i] = (float) c[1]; b[i] = (float) c[2]; }
        void blend(int x, int y, double[] c, double a) {
            if (x < 0 || y < 0 || x >= w || y >= h || a <= 0) return;
            int i = y * w + x; a = Math.min(1, a);
            r[i] = (float) lerp(r[i], c[0], a); g[i] = (float) lerp(g[i], c[1], a); b[i] = (float) lerp(b[i], c[2], a);
        }
        void add(int x, int y, double[] c, double a) {
            if (x < 0 || y < 0 || x >= w || y >= h || a <= 0) return;
            int i = y * w + x;
            r[i] += (float) (c[0] * a); g[i] += (float) (c[1] * a); b[i] += (float) (c[2] * a);
        }
        double[] get(int x, int y) { x = Math.max(0, Math.min(w - 1, x)); y = Math.max(0, Math.min(h - 1, y)); int i = y * w + x; return new double[]{r[i], g[i], b[i]}; }
        BufferedImage toImage(double grain, long seed) {
            Random rnd = new Random(seed);
            BufferedImage im = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                int i = y * w + x;
                double n = (rnd.nextDouble() + rnd.nextDouble() - 1) * grain; // triangular dither: kills banding
                int R = (int) Math.round(Math.max(0, Math.min(255, r[i] * 255 + n)));
                int G = (int) Math.round(Math.max(0, Math.min(255, g[i] * 255 + n)));
                int B = (int) Math.round(Math.max(0, Math.min(255, b[i] * 255 + n)));
                im.setRGB(x, y, (R << 16) | (G << 8) | B);
            }
            return im;
        }
    }
    static double[] c(int hex) { return new double[]{((hex >> 16) & 255) / 255.0, ((hex >> 8) & 255) / 255.0, (hex & 255) / 255.0}; }
    static double[] mix(double[] a, double[] b, double t) { return new double[]{lerp(a[0], b[0], t), lerp(a[1], b[1], t), lerp(a[2], b[2], t)}; }
    static double[] grad3(double t, double[] a, double[] b, double[] c, double mid) {
        return t < mid ? mix(a, b, t / mid) : mix(b, c, (t - mid) / (1 - mid));
    }
    static void stars(Img im, long seed, int count, double maxY, double bright) {
        Random r = new Random(seed);
        for (int i = 0; i < count; i++) {
            int x = r.nextInt(im.w);
            double fy = Math.pow(r.nextDouble(), 1.6) * maxY;
            int y = (int) (fy * im.h);
            double m = Math.pow(r.nextDouble(), 3) * bright * (1 - fy / maxY * 0.6);
            double[] col = mix(c(0xFFFFFF), r.nextBoolean() ? c(0xBFD4FF) : c(0xFFE6C8), r.nextDouble() * 0.6);
            im.add(x, y, col, m);
            if (m > 0.45) { // a few bigger stars with soft halo
                for (int dy = -2; dy <= 2; dy++) for (int dx = -2; dx <= 2; dx++) {
                    double d = Math.sqrt(dx * dx + dy * dy);
                    if (d > 0) im.add(x + dx, y + dy, col, m * 0.25 / (d * d));
                }
            }
        }
    }
    /** 1-D ridge line used for mountains. */
    static double ridge(double x, double seed, int oct) {
        return fbm(x, seed, oct);
    }

    // ------------------------------------------------------------------ scenes


    /** Default wallpaper: alpine lake at sunset with alpenglow on snowy peaks and a mirror reflection. */
    static BufferedImage lake() {
        Img im = new Img(W, H);
        int shore = (int) (H * 0.585);
        double sunX = W * 0.40, sunY = shore - H * 0.02;
        double[] top = c(0x111830), upper = c(0x3B3054), mid = c(0xB85A5E), low = c(0xF59A52), glow = c(0xFFD39A);
        for (int y = 0; y < shore; y++) {
            double t = y / (double) shore;
            double[] col = t < 0.30 ? mix(top, upper, t / 0.30) : t < 0.58 ? mix(upper, mid, (t - 0.30) / 0.28)
                : t < 0.85 ? mix(mid, low, (t - 0.58) / 0.27) : mix(low, glow, (t - 0.85) / 0.15);
            for (int x = 0; x < W; x++) {
                double d = Math.hypot((x - sunX) / W, (y - sunY) / H * 1.4);
                im.set(x, y, mix(col, c(0xFFD9A0), Math.exp(-d * 7) * 0.55));
            }
        }
        // Long clouds: dark plum tops, lit orange/pink undersides.
        for (int y = (int) (H * 0.05); y < shore - H * 0.12; y++) for (int x = 0; x < W; x++) {
            double n = fbm(x * 0.0012 + 3, y * 0.0085, 6);
            double band = Math.exp(-Math.pow((y - H * 0.24) / (H * 0.17), 2));
            double a = smooth(-0.05, 0.3, n) * band;
            if (a <= 0.01) continue;
            double under = smooth(-0.05, 0.25, fbm(x * 0.0012 + 3, (y + 14) * 0.0085, 6) - n);
            double litness = clamp(1.2 - Math.hypot((x - sunX) / W, (y - sunY) / H) * 1.3);
            double[] cc = mix(c(0x3A2C4A), mix(c(0xF2896A), c(0xFFC48A), litness), Math.min(1, under * 1.5 + 0.25 * litness));
            im.blend(x, y, cc, a * 0.95);
        }
        stars(im, 51, 260, 0.18, 0.45);
        // Distant hazy range.
        mountainLayer(im, (int) (H * 0.50), H * 0.07, c(0x8C6A86), 2.2, 11.3, 5, shore);
        // Main peaks: ridged noise → sharp summits; snow with warm alpenglow on sunward faces.
        double[] ridge = new double[W];
        for (int x = 0; x < W; x++) {
            double fx = x / (double) W;
            double r = 0;
            double amp = 1, f = 1.6;
            for (int o = 0; o < 7; o++) { r += amp * (1 - Math.abs(noise(fx * f + 21.7, o * 3.1))); amp *= 0.52; f *= 2.2; }
            double massif = Math.exp(-Math.pow((fx - 0.60) / 0.30, 2)) * 1.0 + 0.30 + 0.25 * Math.exp(-Math.pow((fx - 0.2) / 0.12, 2));
            ridge[x] = shore - H * 0.05 - H * 0.33 * massif * Math.pow(r / 1.95, 3.0);
        }
        for (int x = 0; x < W; x++) {
            for (int y = (int) ridge[x]; y < shore; y++) {
                double depth = (y - ridge[x]) / (double) (shore - ridge[x] + 1);
                double alt = (shore - y) / (H * 0.36);
                // Faceted rock faces from a 2-D ridged field; lit from the sun side (left).
                // Ridges run diagonally down from the summits: shear the field so facets follow the slopes.
                double fx = x * 0.0028 + y * 0.0011, fy = y * 0.0042;
                double hl = ridged(fx - 0.006, fy), hr = ridged(fx + 0.006, fy);
                double face = smooth(0.2, 0.8, 0.5 + (hl - hr) * 9.0);
                double gully = ridged(fx * 2.6 + 4, fy * 2.6 + 9);
                double snow = smooth(0.45, 0.62, alt + (gully - 0.55) * 0.45 - depth * 0.35);
                double[] rockCol = mix(c(0x1E1827), c(0x4E333D), face);
                double[] snowShade = c(0x4E5180), snowLit = mix(c(0xFF9F6A), c(0xFFE1C4), clamp(alt * 0.9));
                double[] col = mix(rockCol, mix(snowShade, snowLit, face), snow);
                col = mix(col, c(0x7A5A7C), Math.pow(depth, 1.6) * 0.6); // valley haze
                im.blend(x, y, col, Math.min(1, y - ridge[x] + 0.5));
            }
        }
        // Forested foothills with pines on both sides.
        Random r = new Random(9);
        for (int side = 0; side < 2; side++) {
            for (int x = 0; x < W; x++) {
                double fx = x / (double) W;
                double hill = side == 0 ? Math.exp(-Math.pow(fx / 0.28, 2)) : Math.exp(-Math.pow((1 - fx) / 0.24, 2));
                int topY = (int) (shore - H * 0.11 * hill - H * 0.012 * fbm(fx * 6, side * 5, 3));
                for (int y = topY; y < shore; y++) im.blend(x, y, c(0x120F18), Math.min(1, y - topY + 0.5));
                if (hill > 0.08 && r.nextDouble() < 0.09) {
                    double h = H * (0.025 + 0.05 * hill) * (0.6 + r.nextDouble() * 0.6);
                    double w = h * 0.28;
                    for (int yy = (int) (topY - h); yy < topY + 2; yy++) {
                        double f = (yy - (topY - h)) / h;
                        double half = w * f;
                        for (int xx = (int) (x - half - 1); xx <= x + half + 1; xx++)
                            im.blend(xx, yy, c(0x120F18), Math.min(1, half - Math.abs(xx - x) + 0.5));
                    }
                }
            }
        }
        // Lake: rippled mirror of everything above, darkened, with a warm sun path.
        for (int y = shore; y < H; y++) {
            double fy = (y - shore) / (double) (H - shore);
            for (int x = 0; x < W; x++) {
                double ripple = noise(x * 0.0025, y * 0.12) * (2 + 14 * fy) + noise(x * 0.01, y * 0.4) * 2;
                double[] refl = im.get((int) (x + ripple * 0.3), 2 * shore - y + (int) ripple);
                double[] col = mix(c(0x07070D), refl, 0.78 - 0.5 * fy);
                double path = Math.exp(-Math.abs(x - sunX) / (60 + fy * 300)) * smooth(0.1, 0.6, noise(x * 0.004, y * 0.25) + 0.3);
                col = mix(col, c(0xFFC287), path * 0.35 * (1 - fy));
                im.set(x, y, col);
            }
        }
        // Foreground shore stones, bottom corners.
        for (int x = 0; x < W; x++) {
            double fx = x / (double) W;
            double edge = Math.max(Math.exp(-Math.pow(fx / 0.18, 2)), Math.exp(-Math.pow((1 - fx) / 0.15, 2)));
            int topY = (int) (H - H * 0.10 * edge - H * 0.015 * fbm(fx * 9, 3, 3));
            for (int y = topY; y < H; y++) im.blend(x, y, c(0x08070B), Math.min(1, y - topY + 0.5));
        }
        return im.toImage(1.6, 8);
    }

    static BufferedImage aurora() {
        Img im = new Img(W, H);
        int horizon = (int) (H * 0.80);
        double[] top = c(0x02040B), mid = c(0x07162A), low = c(0x0D2A3A);
        for (int y = 0; y < H; y++) {
            double[] col = grad3(y / (double) horizon, top, mid, low, 0.55);
            for (int x = 0; x < W; x++) im.set(x, y, col);
        }
        stars(im, 11, 2600, 0.75, 0.95);
        // Aurora curtains: bright lower edge, rays fading upward, colour shifting green → violet.
        double[] green = c(0x4DFFB0), teal = c(0x3BC9DB), violet = c(0x8A6CFF);
        for (int k = 0; k < 3; k++) {
            double base = H * (0.40 + k * 0.06), amp = H * (0.11 - k * 0.02), seed = 3.1 + k * 7.7;
            double strength = 0.95 - k * 0.2;
            for (int x = 0; x < W; x++) {
                double fx = x / (double) W;
                double edge = base + amp * fbm(fx * 2.2 + k, seed, 4) * 1.8 + Math.sin(fx * 5.0 + k) * H * 0.04;
                double rays = 0.55 + 0.45 * (0.5 + 0.5 * fbm(fx * 38 + k * 3, seed * 2, 3));
                double env = smooth(-0.1, 0.25, fx) * smooth(1.1, 0.7, fx) * (0.6 + 0.4 * fbm(fx * 3, seed + 9, 2));
                int y0 = (int) (edge - H * 0.32), y1 = (int) (edge + 18);
                for (int y = Math.max(0, y0); y < Math.min(horizon, y1); y++) {
                    double d = (edge - y) / (H * 0.32); // 0 at edge, 1 at top of curtain
                    double a = d < 0 ? Math.exp(d * 9) : Math.pow(1 - d, 2.2) * (0.25 + 0.75 * Math.exp(-d * 4.5));
                    double[] col = d < 0.25 ? mix(green, teal, d / 0.25) : mix(teal, violet, Math.min(1, (d - 0.25) / 0.6));
                    im.add(x, y, col, a * rays * env * strength);
                }
            }
        }
        // Mountain silhouettes.
        mountainLayer(im, horizon - (int) (H * 0.13), H * 0.09, c(0x0B1A2C), 1.7, 4.2, 5, horizon);
        mountainLayer(im, horizon - (int) (H * 0.05), H * 0.07, c(0x040A12), 3.3, 9.1, 6, horizon);
        // Still lake reflecting the sky.
        for (int y = horizon; y < H; y++) {
            double fy = (y - horizon) / (double) (H - horizon);
            for (int x = 0; x < W; x++) {
                double ripple = noise(x * 0.004, y * 0.09) * 10 * (0.3 + fy);
                double[] s = im.get((int) (x + ripple), 2 * horizon - y - 2);
                double dark = 0.42 - 0.22 * fy;
                im.set(x, y, mix(c(0x02050A), s, dark));
            }
        }
        return im.toImage(1.6, 1);
    }

    static void mountainLayer(Img im, int baseY, double height, double[] col, double freq, double seed, int oct, int bottom) {
        for (int x = 0; x < im.w; x++) {
            double fx = x / (double) im.w;
            double r = ridge(fx * freq, seed, oct);
            double peak = baseY - height * (0.6 + 1.2 * r);
            for (int y = Math.max(0, (int) peak); y < bottom; y++) {
                double a = Math.min(1, y - peak + 0.5); // anti-aliased top edge
                im.blend(x, y, col, a);
            }
        }
    }

    static BufferedImage alpine() {
        Img im = new Img(W, H);
        double[] top = c(0x1A2746), mid = c(0x9C7A9A), glow = c(0xF6C9A0);
        double sunX = W * 0.66, sunY = H * 0.50;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            double t = y / (H * 0.56);
            double[] col = grad3(Math.min(1, t), top, mid, glow, 0.62);
            double d = Math.hypot(x - sunX, (y - sunY) * 1.6) / W;
            col = mix(col, c(0xFFE7C4), Math.exp(-d * 9) * 0.65);
            im.set(x, y, col);
        }
        // Thin high clouds lit from below.
        for (int y = 0; y < H * 0.45; y++) for (int x = 0; x < W; x++) {
            double n = fbm(x * 0.0016, y * 0.006 + 3, 5);
            double a = smooth(0.08, 0.45, n) * 0.35 * smooth(0, H * 0.15, y);
            im.blend(x, y, c(0xF3B9A6), a);
        }
        double[] haze = c(0xD3A8A8), deep = c(0x141E36);
        int layers = 5;
        for (int i = 0; i < layers; i++) {
            double t = i / (double) (layers - 1);
            double[] col = mix(haze, deep, Math.pow(t, 0.8));
            int base = (int) (H * (0.56 + 0.095 * i));
            mountainLayer(im, base, H * (0.16 - 0.018 * i), col, 1.2 + i * 0.9, 1.7 + i * 5.3, 6, H);
            // Mist pooling at the foot of each layer.
            for (int y = base - (int) (H * 0.05); y < Math.min(H, base + (int) (H * 0.06)); y++) {
                double a = Math.exp(-Math.abs(y - base) / (H * 0.025)) * 0.32 * (1 - t * 0.5);
                for (int x = 0; x < W; x++) {
                    double n = 0.6 + 0.4 * noise(x * 0.004, y * 0.02 + i);
                    im.blend(x, y, haze, a * n);
                }
            }
        }
        return im.toImage(1.6, 2);
    }

    static BufferedImage dunes() {
        Img im = new Img(W, H);
        double[] top = c(0x2A1C40), mid = c(0xC9675A), low = c(0xF4B577);
        for (int y = 0; y < H; y++) {
            double[] col = grad3(Math.min(1, y / (H * 0.55)), top, mid, low, 0.55);
            for (int x = 0; x < W; x++) im.set(x, y, col);
        }
        double sunX = W * 0.30, sunY = H * 0.47;
        for (int y = 0; y < H * 0.6; y++) for (int x = 0; x < W; x++) {
            double d = Math.hypot(x - sunX, y - sunY);
            im.add(x, y, c(0xFFD7A0), Math.exp(-d / 220) * 0.55);
            if (d < 46) im.blend(x, y, c(0xFFF1D6), clamp(46 - d) * 0.9);
        }
        stars(im, 21, 500, 0.25, 0.5);
        int layers = 5;
        for (int i = 0; i < layers; i++) {
            double t = i / (double) (layers - 1);
            double base = H * (0.52 + i * 0.11);
            double amp = H * (0.05 + 0.03 * i);
            double[] lit = mix(c(0xE79A6A), c(0xD9824F), t), shade = mix(c(0x8C4F62), c(0x2C1630), t);
            double ph = i * 1.9, fr = 0.7 + i * 0.32;
            double[] prof = new double[W];
            for (int x = 0; x < W; x++) {
                double fx = x / (double) W * fr + ph;
                // Asymmetric dune profile: gentle windward slope, sharp crest.
                double s = Math.pow(0.5 + 0.5 * Math.sin(fx * Math.PI * 2 + 0.9 * Math.sin(fx * 2.3 + i)), 1.4) * (0.7 + 0.3 * Math.sin(fx * 1.3 + i * 2.0));
                prof[x] = base - amp * s - amp * 0.3 * fbm(fx, i + 5, 3);
            }
            for (int x = 0; x < W; x++) {
                double slope = prof[Math.min(W - 1, x + 2)] - prof[Math.max(0, x - 2)];
                double litAmt = smooth(-3, 3, slope);
                for (int y = (int) prof[x]; y < H; y++) {
                    double depth = (y - prof[x]) / (H * 0.25);
                    double[] col = mix(mix(shade, lit, litAmt * Math.exp(-depth * 1.5)), shade, Math.min(1, depth * 0.6));
                    double ripples = 0.04 * noise(x * 0.02, y * 0.25 + i * 10) * (1 - t * 0.3);
                    col = new double[]{col[0] + ripples, col[1] + ripples * 0.8, col[2] + ripples * 0.6};
                    im.blend(x, y, col, Math.min(1, y - prof[x] + 0.5));
                }
            }
        }
        return im.toImage(1.6, 3);
    }

    static BufferedImage ocean() {
        Img im = new Img(W, H);
        int horizon = (int) (H * 0.60);
        double[] top = c(0x0B1230), mid = c(0x3B3B70), low = c(0xEE8F6E);
        double sunX = W * 0.5;
        for (int y = 0; y < horizon; y++) for (int x = 0; x < W; x++) {
            double[] col = grad3(y / (double) horizon, top, mid, low, 0.6);
            double d = Math.hypot(x - sunX, (y - horizon) * 2.2) / W;
            col = mix(col, c(0xFFD2A1), Math.exp(-d * 6) * 0.7);
            im.set(x, y, col);
        }
        // Long, lit clouds.
        for (int y = (int) (H * 0.08); y < horizon - 20; y++) for (int x = 0; x < W; x++) {
            double n = fbm(x * 0.0011, y * 0.009, 5);
            double band = Math.exp(-Math.pow((y - H * 0.33) / (H * 0.12), 2));
            double a = smooth(0.05, 0.4, n) * band * 0.75;
            double fy = y / (double) horizon;
            double[] cc = mix(c(0x2E2850), c(0xF2A08C), Math.pow(fy, 1.4));
            im.blend(x, y, cc, a);
        }
        stars(im, 31, 700, 0.30, 0.6);
        // Sea: reflection + horizontal wave streaks + sun glitter path.
        for (int y = horizon; y < H; y++) {
            double fy = (y - horizon) / (double) (H - horizon);
            for (int x = 0; x < W; x++) {
                double wave = noise(x * 0.003 / (0.2 + fy), y * 0.35 / (0.3 + fy * 2));
                double[] refl = im.get(x, 2 * horizon - y + (int) (wave * 12));
                double[] col = mix(c(0x070E1E), refl, 0.55 - fy * 0.35);
                double path = Math.exp(-Math.abs(x - sunX) / (40 + fy * 380));
                double glitter = smooth(0.15, 0.55, wave) * path * (1 - fy * 0.4);
                col = mix(col, c(0xFFD9AE), glitter * 0.85);
                im.set(x, y, col);
            }
        }
        return im.toImage(1.6, 4);
    }

    static BufferedImage nebula() {
        Img im = new Img(W, H);
        double[] a = c(0x05060F), b = c(0x0E0A2A);
        double[] p1 = c(0xC2378A), p2 = c(0x3C5BD8), p3 = c(0x27C2C4), p4 = c(0xFFC48A);
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            double fx = x / (double) H, fy = y / (double) H;
            double wx = fbm(fx * 1.6 + 5, fy * 1.6, 4), wy = fbm(fx * 1.6, fy * 1.6 + 9, 4);
            double n = fbm(fx * 1.9 + wx * 1.4, fy * 1.9 + wy * 1.4, 6);
            double m = fbm(fx * 3.5 + wy, fy * 3.5 - wx, 4);
            double[] col = mix(a, b, 0.5 + 0.5 * wy);
            double band = Math.exp(-Math.pow((fy - 0.38 - 0.3 * (fx / 1.78 - 0.5)) / 0.42, 2));
            double dens = smooth(-0.3, 0.35, n) * band;
            col = mix(col, mix(p2, p1, smooth(-0.3, 0.4, m)), dens * 0.95);
            col = mix(col, p3, smooth(0.25, 0.7, m) * dens * 0.6);
            col = mix(col, p4, smooth(0.45, 0.8, n) * band * 0.45);
            double dust = smooth(0.15, 0.55, fbm(fx * 6, fy * 6 + 3, 4)) * band * 0.35; // dark dust lanes
            col = mix(col, a, dust);
            im.set(x, y, col);
        }
        stars(im, 41, 4200, 1.0, 1.0);
        return im.toImage(1.8, 5);
    }

    static BufferedImage forest() {
        Img im = new Img(W, H);
        double[] top = c(0x8DA3B3), low = c(0xE3E2D8);
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            double t = y / (double) H;
            double[] col = mix(top, low, Math.pow(t, 0.7));
            double light = Math.exp(-Math.hypot(x - W * 0.2, y + H * 0.1) / (W * 0.45));
            col = mix(col, c(0xFFF6E2), light * 0.5);
            im.set(x, y, col);
        }
        double[] fog = c(0xD5D9D2);
        int layers = 6;
        Random r = new Random(5);
        for (int i = 0; i < layers; i++) {
            double t = i / (double) (layers - 1);
            double[] col = mix(c(0x9BAAAE), c(0x0E1A1C), Math.pow(t, 0.9));
            double base = H * (0.50 + i * 0.085);
            double treeH = H * (0.10 + 0.07 * t);
            double hill = H * 0.04;
            double[] top2 = new double[W];
            for (int x = 0; x < W; x++) top2[x] = base - hill * fbm(x / (double) W * 2 + i, i * 3, 3);
            // Pines: narrow triangles with slightly ragged edges.
            double spacing = 14 + 30 * (1 - t);
            for (double tx = -20; tx < W + 20; tx += spacing * (0.6 + r.nextDouble() * 0.8)) {
                int cx = (int) tx;
                double h = treeH * (0.55 + r.nextDouble() * 0.6);
                double w = h * (0.22 + r.nextDouble() * 0.06);
                double gy = top2[Math.max(0, Math.min(W - 1, cx))];
                for (int y = (int) (gy - h); y < gy + 4; y++) {
                    double f = (y - (gy - h)) / h;
                    double half = w * f * (0.85 + 0.15 * Math.sin(y * 0.9 + cx));
                    for (int x = (int) (cx - half - 1); x <= cx + half + 1; x++) {
                        double edge = Math.min(1, half - Math.abs(x - cx) + 0.5);
                        im.blend(x, y, col, edge);
                    }
                }
            }
            for (int x = 0; x < W; x++) for (int y = (int) top2[x]; y < H; y++) im.blend(x, y, col, 1);
            // Fog band in front of every layer.
            for (int y = (int) (base - treeH); y < H; y++) {
                double a = Math.exp(-Math.abs(y - base) / (H * 0.05)) * (0.45 - t * 0.25);
                for (int x = 0; x < W; x++) im.blend(x, y, fog, a * (0.7 + 0.3 * noise(x * 0.003, y * 0.01 + i)));
            }
        }
        return im.toImage(1.6, 6);
    }

    static BufferedImage silk() {
        Img im = new Img(W, H);
        double[] d0 = c(0x070A1C), d1 = c(0x1B1E5A), d2 = c(0x4B2C83), d3 = c(0x2A8FB0);
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            double fx = x / (double) H, fy = y / (double) H;
            double fold = Math.sin(fx * 3.1 + Math.sin(fy * 3.4 + fx * 0.9) * 1.8 + fbm(fx * 0.8, fy * 0.8, 3) * 1.2);
            double fold2 = Math.sin(fx * 1.1 - fy * 1.9 + Math.cos(fx * 1.7) * 1.3);
            double v = 0.5 + 0.35 * fold + 0.15 * fold2;
            double[] col = v < 0.5 ? mix(d0, d1, v / 0.5) : mix(d1, d2, (v - 0.5) / 0.5);
            col = mix(col, d3, smooth(0.6, 1.0, 0.5 + 0.5 * fold2) * 0.35 * smooth(0.3, 0.9, fy));
            double spec = Math.pow(Math.max(0, fold), 6) * 0.10 * smooth(0.0, 0.6, fy);
            col = mix(col, c(0xD8E2FF), spec);
            col = mix(col, d0, smooth(0.55, 1.1, fy) * 0.5);
            im.set(x, y, col);
        }
        return im.toImage(1.8, 7);
    }

    // ------------------------------------------------------------------ icon / banner

    static void paintMark(Graphics2D g, double cx, double cy, double r) {
        // The "aura": a luminous ring with a soft glow and a small bright core.
        for (int i = 18; i >= 1; i--) {
            double rr = r * (1 + i * 0.045);
            float a = (float) (0.035 * (19 - i) / 18.0);
            g.setColor(new Color(0.55f, 0.8f, 1f, a));
            g.setStroke(new BasicStroke((float) (r * 0.12)));
            g.draw(new Ellipse2D.Double(cx - rr, cy - rr, rr * 2, rr * 2));
        }
        g.setPaint(new GradientPaint((float) (cx - r), (float) (cy - r), new Color(0x7CF2D0),
            (float) (cx + r), (float) (cy + r), new Color(0x8A7CFF)));
        g.setStroke(new BasicStroke((float) (r * 0.16), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2));
        double d = r * 0.22;
        g.setPaint(new RadialGradientPaint(new Point2D.Double(cx, cy), (float) (d * 2.2),
            new float[]{0f, 0.35f, 1f}, new Color[]{Color.WHITE, new Color(1f, 1f, 1f, 0.6f), new Color(1f, 1f, 1f, 0f)}));
        g.fill(new Ellipse2D.Double(cx - d * 2.2, cy - d * 2.2, d * 4.4, d * 4.4));
    }

    static Graphics2D hq(BufferedImage im) {
        Graphics2D g = im.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return g;
    }

    static BufferedImage icon(int size) {
        int s = size * 4; // supersample
        BufferedImage big = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = hq(big);
        RoundRectangle2D shape = new RoundRectangle2D.Double(s * 0.04, s * 0.04, s * 0.92, s * 0.92, s * 0.36, s * 0.36);
        g.setPaint(new GradientPaint(0, 0, new Color(0x1B2350), s, s, new Color(0x05070F)));
        g.fill(shape);
        g.setClip(shape);
        paintMark(g, s / 2.0, s / 2.0, s * 0.24);
        g.dispose();
        return scale(big, size, size);
    }

    static BufferedImage banner(int w, int h) {
        int k = 4, W2 = w * k, H2 = h * k;
        BufferedImage big = new BufferedImage(W2, H2, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = hq(big);
        g.setPaint(new GradientPaint(0, 0, new Color(0x141B3E), W2, H2, new Color(0x04060D)));
        g.fillRect(0, 0, W2, H2);
        g.setPaint(new RadialGradientPaint(new Point2D.Double(W2 * 0.25, H2 * 0.5), (float) (H2 * 0.9),
            new float[]{0f, 1f}, new Color[]{new Color(0x3A7CF2D0, true), new Color(0x007CF2D0, true)}));
        g.fillRect(0, 0, W2, H2);
        paintMark(g, W2 * 0.24, H2 * 0.5, H2 * 0.2);
        g.setColor(Color.WHITE);
        Font f = new Font("Helvetica Neue", Font.PLAIN, (int) (H2 * 0.23));
        java.util.Map<java.awt.font.TextAttribute, Object> attrs = new java.util.HashMap<>();
        attrs.put(java.awt.font.TextAttribute.TRACKING, 0.32);
        g.setFont(f.deriveFont(attrs));
        g.drawString("AURA", (float) (W2 * 0.43), (float) (H2 * 0.585));
        g.setColor(new Color(255, 255, 255, 140));
        g.setFont(new Font("Helvetica Neue", Font.PLAIN, (int) (H2 * 0.10)).deriveFont(attrs));
        g.drawString("TV", (float) (W2 * 0.435), (float) (H2 * 0.75));
        g.dispose();
        return scale(big, w, h);
    }

    static BufferedImage scale(BufferedImage src, int w, int h) {
        BufferedImage cur = src;
        while (cur.getWidth() / 2 >= w) { // progressive halving = high quality downscale
            BufferedImage next = new BufferedImage(cur.getWidth() / 2, cur.getHeight() / 2, src.getType());
            Graphics2D g = next.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(cur, 0, 0, next.getWidth(), next.getHeight(), null);
            g.dispose();
            cur = next;
        }
        return cur;
    }

    static void save(BufferedImage im, File f) throws Exception {
        ImageWriter wr = ImageIO.getImageWritersByFormatName("jpg").next();
        ImageWriteParam p = wr.getDefaultWriteParam();
        p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        p.setCompressionQuality(0.88f);
        try (ImageOutputStream os = ImageIO.createImageOutputStream(f)) {
            wr.setOutput(os);
            wr.write(null, new IIOImage(im, null, null), p);
        }
        wr.dispose();
        System.out.println(f.getName() + " " + f.length() / 1024 + " KB");
    }
}
