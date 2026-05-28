package com.example.myapplication;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Handler;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.ImageView;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;


public class Producer {
    private static final String TAG = "Producer";
    private final ExecutorService genRenderExecutor;
    private final ScheduledExecutorService producerScheduler;
    private final AtomicInteger seqCounter;
    private final Random rnd = new Random();
    private final java.util.concurrent.ConcurrentHashMap<Integer, Boolean> producedLoggedSeqs;
    private final java.util.concurrent.ConcurrentHashMap<Integer, MainActivity.ProducedMeta> producedMetaMap;
    private final CsvWorker csvWorker;
    private final Handler mainHandler;
    private final android.widget.ImageView qrImage;
    private final int qrVersion;
    private final ErrorCorrectionLevel qrEcl;
    private final int modulePx;
    private final int TARGET_HZ;

    private final java.util.function.BiFunction<Integer, ErrorCorrectionLevel, Integer> estimateMaxEncodableBodyLenLookupFn;

    private volatile java.util.concurrent.ScheduledFuture<?> producerFuture = null;

    public Producer(ExecutorService genRenderExecutor,
                    ScheduledExecutorService producerScheduler,
                    AtomicInteger seqCounter,
                    java.util.concurrent.ConcurrentHashMap<Integer, Boolean> producedLoggedSeqs,
                    java.util.concurrent.ConcurrentHashMap<Integer, MainActivity.ProducedMeta> producedMetaMap,
                    CsvWorker csvWorker,
                    Handler mainHandler,
                    android.widget.ImageView qrImage,
                    int qrVersion,
                    ErrorCorrectionLevel qrEcl,
                    int modulePx,
                    int TARGET_HZ,
                    java.util.function.BiFunction<Integer, ErrorCorrectionLevel, Integer> estimateMaxEncodableBodyLenLookupFn) {
        this.genRenderExecutor = genRenderExecutor;
        this.producerScheduler = producerScheduler;
        this.seqCounter = seqCounter;
        this.producedLoggedSeqs = producedLoggedSeqs;
        this.producedMetaMap = producedMetaMap;
        this.csvWorker = csvWorker;
        this.mainHandler = mainHandler;
        this.qrImage = qrImage;
        this.qrVersion = qrVersion;
        this.qrEcl = qrEcl;
        this.modulePx = modulePx;
        this.TARGET_HZ = TARGET_HZ;
        this.estimateMaxEncodableBodyLenLookupFn = estimateMaxEncodableBodyLenLookupFn;
    }

    public void startProducer() {
        if (producerFuture != null && !producerFuture.isDone()) producerFuture.cancel(true);
        final long intervalMs = 1000L / Math.max(1, TARGET_HZ);
        producerFuture = producerScheduler.scheduleWithFixedDelay(() -> {
            genRenderExecutor.submit(() -> {
                final int seq = nextSeq();
                final long producedWallMs = System.currentTimeMillis();/*生成時刻*/

                int useLen = 120;/*最低の長さ*/
                int raw = estimateMaxEncodableBodyLenLookupFn.apply(qrVersion, qrEcl);
                final int overhead = 8 /* seq */ + 1 /* '|' */ + 13 /* ts */ + 1 /* '|' */ + 8 /* crc */ + 2;
                synchronized (this) { if (raw > 0) useLen = Math.max(0, raw - overhead); }

                String payloadBody = randomAlphaNumericLen(useLen);
                String finalPayload = makePayloadWithSeqAndTs(seq, producedWallMs, payloadBody);/*sec+生成時刻*/

                Bitmap bmp;
                try { bmp = renderQrBitmapFromModules(finalPayload, qrVersion, qrEcl, modulePx); }
                catch (Exception e) { Log.e(TAG, "QR render failed", e); bmp = renderQrBitmapFastFallback(finalPayload, modulePx); }

                producedMetaMap.put(seq, new MainActivity.ProducedMeta(producedWallMs, TARGET_HZ, finalPayload));

                final Bitmap shown = bmp;
                mainHandler.post(() -> {
                    try { qrImage.setImageBitmap(shown);
                        qrImage.setAdjustViewBounds(false);
                        qrImage.setScaleType(ImageView.ScaleType.CENTER);
                        ViewGroup.LayoutParams lp = qrImage.getLayoutParams();
                        lp.width = shown.getWidth();
                        lp.height = shown.getHeight();
                        qrImage.setLayoutParams(lp);
                    }
                    catch (Exception e) { Log.e(TAG, "setImageBitmap failed", e); }
                    long displayedMs = System.currentTimeMillis();/*描画時間*/
                    MainActivity.ProducedMeta pm = producedMetaMap.remove(seq);/*キューから削除*/
                    long producedMs = pm != null ? pm.producedWallMs : -1;
                    int genHz = pm != null ? pm.genHz : TARGET_HZ;
                    String payload = pm != null ? pm.payload : finalPayload;
                    if (seq >= 101 && seq <= 600) {/*ハードコーディング*/
                        Boolean prev = producedLoggedSeqs.putIfAbsent(seq, Boolean.TRUE);/*重複判定*/
                        if (prev == null) csvWorker.appendProducedFinal(seq, producedMs, displayedMs, genHz);
                    }
                });
            });
        }, 0, intervalMs, java.util.concurrent.TimeUnit.MILLISECONDS);/*指定時間ごとに描画*/
    }

    public void stopProducer() {
        try {
            if (producerFuture != null) producerFuture.cancel(true);
        } catch (Exception ignored) {}
    }

    int nextSeq() { return seqCounter.getAndIncrement(); }

    private String randomAlphaNumericLen(int len) {
        if (len <= 0) return "";
        final String alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
        return sb.toString();
    }

    String crc32Hex(String s) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(s.getBytes());
        return Long.toHexString(crc.getValue()).toUpperCase(Locale.US);
    }

    String makePayloadWithSeqAndTs(int seq, long producedWallMs, String body) {
        if (body == null) body = "";
        String combined = String.format(Locale.US, "%08d|%d|%s", seq, producedWallMs, body);
        String crcHex = crc32Hex(combined);
        return combined + "|" + crcHex;
    }

    private Bitmap renderQrBitmapFromModules(String contents, int version, ErrorCorrectionLevel ecl, int modulePixels) throws Exception {
        QRCodeWriter writer = new QRCodeWriter();
        int modules = 21 + (version - 1) * 4;
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.ERROR_CORRECTION, ecl);
        hints.put(EncodeHintType.MARGIN, 4);
        try { hints.put(EncodeHintType.QR_VERSION, version); } catch (Exception ignored) {}
        BitMatrix matrix = writer.encode(contents, BarcodeFormat.QR_CODE, modules, modules, hints);
        int w = matrix.getWidth(), h = matrix.getHeight();
        Bitmap small = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        int[] smallPixels = new int[w*h];/*モジュールごとに描画*/
        for (int y=0;y<h;y++){
            int base = y*w;
            for (int x=0;x<w;x++){
                smallPixels[base + x] = matrix.get(x,y) ? Color.BLACK : Color.WHITE;
            }
        }
        small.setPixels(smallPixels, 0, w, 0,0,w,h);
        int outW = w * modulePixels, outH = h * modulePixels;/*スケーリング*/
        Bitmap big = Bitmap.createScaledBitmap(small, outW, outH, false);
        try { small.recycle(); } catch (Exception ignored) {}
        return big;
    }

    private Bitmap renderQrBitmapFastFallback(String contents, int modulePixels) {/*フォールバック用*/
        try {
            QRCodeWriter writer = new QRCodeWriter();
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.ERROR_CORRECTION, qrEcl);
            hints.put(EncodeHintType.MARGIN, 4);
            int approxModules = 21 + (qrVersion - 1) * 4;
            BitMatrix matrix = writer.encode(contents, BarcodeFormat.QR_CODE, approxModules, approxModules, hints);
            int w = matrix.getWidth(), h = matrix.getHeight();
            Bitmap small = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            int[] smallPixels = new int[w*h];
            for (int y=0;y<h;y++){
                int base = y*w;
                for (int x=0;x<w;x++){
                    smallPixels[base + x] = matrix.get(x,y) ? Color.BLACK : Color.WHITE;
                }
            }
            small.setPixels(smallPixels, 0, w, 0,0,w,h);
            int outW = w * modulePixels, outH = h * modulePixels;
            Bitmap big = Bitmap.createScaledBitmap(small, outW, outH, false);
            try { small.recycle(); } catch (Exception ignored) {}
            return big;
        } catch (Exception e) {
            Log.e(TAG, "QR fallback render failed", e);
            return Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888);
        }
    }
}
