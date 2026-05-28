package com.example.myapplication;
import static com.example.myapplication.MainActivity.READER_TL;
import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.camera2.CaptureRequest;
import android.os.SystemClock;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.View;
import android.widget.Toast;
import androidx.annotation.OptIn;
import androidx.camera.camera2.interop.Camera2Interop;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.MultiFormatReader;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;


public class Decoder {
    private static final String TAG = "Decoder";

    private final Context context;
    private final PreviewView previewView;
    private final java.util.concurrent.ExecutorService analysisExecutor;
    private final ExecutorService decodeExecutor;
    private final CsvWorker csvWorker;
    private final ConcurrentHashMap<Integer, Boolean> decodeLoggedSeqs;
    private final AtomicBoolean decodeBusy;
    private final int qrVersion;
    private final long DECODE_TIMEOUT_MS;
    private final int DECODE_HZ = 30;/*固定*/
    private final ScheduledExecutorService timeoutScheduler;


    public Decoder(Context context,
                   PreviewView previewView,
                   java.util.concurrent.ExecutorService analysisExecutor,
                   ExecutorService decodeExecutor,
                   CsvWorker csvWorker,
                   ConcurrentHashMap<Integer, Boolean> decodeLoggedSeqs,
                   AtomicBoolean decodeBusy,
                   int qrVersion,
                   long DECODE_TIMEOUT_MS,
                   int modulePx,
                   ScheduledExecutorService timeoutScheduler) {
        this.context = context;
        this.previewView = previewView;
        this.analysisExecutor = analysisExecutor;
        this.decodeExecutor = decodeExecutor;
        this.csvWorker = csvWorker;
        this.decodeLoggedSeqs = decodeLoggedSeqs;
        this.decodeBusy = decodeBusy;
        this.qrVersion = qrVersion;
        this.DECODE_TIMEOUT_MS = DECODE_TIMEOUT_MS;
        this.timeoutScheduler = timeoutScheduler;
    }

    @OptIn(markerClass = ExperimentalCamera2Interop.class)
    @SuppressWarnings("deprecation")
    public void startCameraXDecoding() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            if (context instanceof MainActivity) {
                ((MainActivity) context).requestPermissionLauncher.launch(Manifest.permission.CAMERA);
            }
            return;
        }
        previewView.setVisibility(View.VISIBLE);

        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(context);
        cameraProviderFuture.addListener(() -> {
            ProcessCameraProvider cameraProvider;
            try {
                cameraProvider = cameraProviderFuture.get();
            } catch (Exception e) {
                Log.e(TAG, "Camera provider init failed", e);
                return;
            }
            Preview.Builder previewBuilder = new Preview.Builder();
            Preview preview = previewBuilder.build();

            ImageAnalysis.Builder analysisBuilder = new ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .setTargetResolution(new Size(1080, 1080));


            long exposureMs = 5L; //露光時間
            long exposureNs = TimeUnit.MILLISECONDS.toNanos(exposureMs);
            int iso =100;//感度ISO

            try {
                Camera2Interop.Extender<Preview> previewExt = new Camera2Interop.Extender<>(previewBuilder);
                previewExt.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
                previewExt.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs);
                previewExt.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso);
                previewExt.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true);//露光時間指定
                previewExt.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);//固定フォーカス

                Camera2Interop.Extender<ImageAnalysis> analysisExt = new Camera2Interop.Extender<>(analysisBuilder);
                analysisExt.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
                analysisExt.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs);
                analysisExt.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso);
                analysisExt.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true);
                analysisExt.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);

                Range<Integer> fpsRange = new Range<>(DECODE_HZ, DECODE_HZ);
                analysisExt.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);
            } catch (Exception e) {
                Log.w(TAG, "Manual exposure/settings may not apply: " + e);
            }

            ImageAnalysis analysis = analysisBuilder.build();

            analysis.setAnalyzer(analysisExecutor, image -> {

                if (!((MainActivity) context).decoding.get()) {
                    image.close();
                    return;
                }

                if (!decodeBusy.compareAndSet(false, true)) {//busy_drop
                    csvWorker.appendAnalyze(System.currentTimeMillis(), image.getWidth(), image.getHeight(), -1.0, -1.0, "BUSY_DROP", -1.0, "");
                    image.close();
                    return;
                }

                final long frameUptimeMs = System.currentTimeMillis();
                final int rotation = image.getImageInfo().getRotationDegrees();
                final int srcW = image.getWidth();
                final int srcH = image.getHeight();

                byte[] rotatedY;
                int rotW, rotH;
                byte[] cropY;
                int cropSize;
                double avgLum, sharpnes;
                long sensorWallMs, sensorAgeMs;

                try (image) {
                    try {
                        final long sensorTimestampNs = image.getImageInfo().getTimestamp();
                        final long nowNs = SystemClock.elapsedRealtimeNanos();
                        sensorAgeMs = (nowNs-sensorTimestampNs)/ 1_000_000L  ;
                        if (sensorAgeMs < 0) sensorAgeMs = 0;
                        sensorWallMs = System.currentTimeMillis() - sensorAgeMs;//カメラに映った時刻
                    } catch (Exception ignored) {
                        sensorWallMs = -1L;
                        sensorAgeMs = -1L;
                    }

                    // YUV -> NV21
                    byte[] nv21 = MainActivity.NV21_BUF_TL.get();
                    int expectedLen = srcW * srcH + (srcW * srcH) / 2;
                    if (nv21 == null || nv21.length < expectedLen) {
                        nv21 = new byte[expectedLen];
                        MainActivity.NV21_BUF_TL.set(nv21);
                    }
                    boolean ok = yuv420ToNv21SafeInto(image, nv21);
                    if (!ok) {
                        decodeBusy.set(false);
                        image.close();
                        return;
                    }

                    /*yを推定*/
                    byte[] yPlane = MainActivity.YPLANE_BUF_TL.get();
                    int ySize = srcW * srcH;
                    if (yPlane == null || yPlane.length < ySize) {
                        yPlane = new byte[ySize];
                        MainActivity.YPLANE_BUF_TL.set(yPlane);
                    }
                    extractYPlaneInto(nv21, srcW, srcH, yPlane);

                  /*回転処理*/
                    if (rotation == 90) {
                        rotatedY = rotateY90CW(yPlane, srcW, srcH);
                        rotW = srcH;
                        rotH = srcW;
                    } else if (rotation == 270) {
                        rotatedY = rotateY90CCW(yPlane, srcW, srcH);
                        rotW = srcH;
                        rotH = srcW;
                    } else {
                        rotatedY = yPlane;
                        rotW = srcW;
                        rotH = srcH;
                    }


                    int minDim = Math.min(rotW, rotH);
                    cropSize =  (Math.min(MainActivity.TARGET_SQUARE, minDim));
                    int left = (rotW - cropSize) / 2;
                    int top = (rotH - cropSize) / 2;
                    cropY = new byte[cropSize * cropSize];
                    int di = 0;
                    for (int ry = 0; ry < cropSize; ry++) {
                        int srcRow = (top + ry) * rotW;
                        System.arraycopy(rotatedY, srcRow + left, cropY, di, cropSize);
                        di += cropSize;
                    }

                    avgLum = computeAverageLuminanceSampled(cropY, cropSize, cropSize, Math.max(1, cropSize / 32));//照度
                    sharpnes = computeTenengradSharpness(cropY, cropSize, cropSize);//滑らかさ

                } catch (Exception e) {
                    decodeBusy.set(false);
                    Log.e(TAG, "analyzer preproc exception", e);
                    return;
                }


                final byte[] finalCropY = cropY;
                final int finalCropSize = cropSize;
                final byte[] finalFullY = rotatedY;
                final int finalFullW = rotW;
                final int finalFullH = rotH;
                final long preprocFrameUptime = frameUptimeMs;
                final long preprocSensorWallMs = sensorWallMs;
                final long preprocSensorAgeMs = sensorAgeMs;
                final double finalAvgLum = avgLum;
                final double finalSharp = sharpnes;

                // overall timeout 設定（調整可）
                final int overallTimeoutMs = 100;
                final int stage1TimeoutMs = Math.min(30, overallTimeoutMs / 3); // 25~30ms
                final int stage2TimeoutMs = overallTimeoutMs - stage1TimeoutMs; // 残りを stage2 に確保

                Callable<DecodeOutcome> masterCallable = () -> {
                    long masterStartNs = System.nanoTime();
                    DecodeOutcome outcome = new DecodeOutcome();
                    MultiFormatReader localReader = READER_TL.get();
                    if (localReader == null) { outcome.totalNs = System.nanoTime() - masterStartNs; return outcome; }

                    // Stage1: 中心だけ抽出
                    Callable<Result> stage1Task = () -> {
                        final PlanarYUVLuminanceSource pySrc =
                                new PlanarYUVLuminanceSource(finalCropY, finalCropSize, finalCropSize, 0, 0, finalCropSize, finalCropSize, false);
                        BinaryBitmap bbh = new BinaryBitmap(new HybridBinarizer(pySrc));
                        try { return localReader.decodeWithState(bbh); }
                        finally { try { localReader.reset(); } catch (Exception ignored) {} }
                    };

                    Future<Result> f1 = decodeExecutor.submit(stage1Task);
                    long s1StartNs = System.nanoTime();
                    Result r1 = null;
                    try {
                        r1 = f1.get(stage1TimeoutMs, TimeUnit.MILLISECONDS);
                    } catch (TimeoutException te) {
                        f1.cancel(true);
                        //Log.i(TAG, "stage1 timed out after " + stage1TimeoutMs + " ms");
                    } catch (CancellationException | InterruptedException | ExecutionException ex) {
                        //Log.w(TAG, "stage1 get threw: " + ex.toString());
                        f1.cancel(true);
                    } finally {
                        outcome.stage1Ns = System.nanoTime() - s1StartNs;
                    }

                    if (r1 != null) {
                        outcome.result = r1;
                        outcome.successStage = 1;
                        outcome.totalNs = System.nanoTime() - masterStartNs;
                        return outcome;
                    }

                    // Stage2: 全体
                    Callable<Result> stage2Task = () -> {
                        final PlanarYUVLuminanceSource fullSrc =
                                new PlanarYUVLuminanceSource(finalFullY, finalFullW, finalFullH, 0, 0, finalFullW, finalFullH, false);
                        BinaryBitmap fullBmp = new BinaryBitmap(new HybridBinarizer(fullSrc));

                        try { return localReader.decodeWithState(fullBmp); }
                        finally { try { localReader.reset(); } catch (Exception ignored) {} }
                    };

                    Future<Result> f2 = decodeExecutor.submit(stage2Task);
                    long s2StartNs = System.nanoTime();
                    Result r2 = null;
                    try {
                        r2 = f2.get(stage2TimeoutMs, TimeUnit.MILLISECONDS);
                    } catch (TimeoutException te2) {
                        f2.cancel(true);
                        //Log.i(TAG, "stage2 timed out after " + stage2TimeoutMs + " ms");
                    } catch (CancellationException | InterruptedException | ExecutionException ex2) {
                        //Log.w(TAG, "stage2 get threw: " + ex2.toString());
                        f2.cancel(true);
                    } finally {
                        outcome.stage2Ns = System.nanoTime() - s2StartNs;
                    }

                    if (r2 != null) {
                        outcome.result = r2;
                        outcome.successStage = 2;
                    }
                    outcome.totalNs = System.nanoTime() - masterStartNs;
                    return outcome;
                };


                Future<DecodeOutcome> fut = decodeExecutor.submit(masterCallable);

                final ScheduledFuture<?> canceller = timeoutScheduler.schedule(() -> {
                    if (!fut.isDone()) {
                        Log.w(TAG, "timeoutScheduler: cancelling decode future after " + DECODE_TIMEOUT_MS + " ms");
                        fut.cancel(true);
                    }
                }, DECODE_TIMEOUT_MS, TimeUnit.MILLISECONDS);


                CompletableFuture<DecodeOutcome> cf = new CompletableFuture<>();
                decodeExecutor.submit(() -> {
                    try {
                        DecodeOutcome out = fut.get();
                        if (!cf.isDone()) cf.complete(out);
                    } catch (CancellationException ce) {
                         Log.w(TAG, "decodeExecutor: fut.get() cancelled", ce);
                        if (!cf.isDone()) cf.completeExceptionally(ce);
                    } catch (InterruptedException ie) {
                         Log.w(TAG, "decodeExecutor: fut.get() interrupted", ie);
                        if (!cf.isDone()) cf.completeExceptionally(ie);
                        Thread.currentThread().interrupt();
                    } catch (ExecutionException ee) {
                        Log.e(TAG, "decodeExecutor: fut.get() execution exception", ee);
                        if (!cf.isDone()) cf.completeExceptionally(ee);
                    } finally {
                        try { canceller.cancel(false); } catch (Exception ignored) {}
                    }
                });

                /*ログ処理*/
                cf.whenComplete((outcome, ex) -> {
                    try {
                        boolean timedOut = (ex != null && (ex instanceof CancellationException || ex.getCause() instanceof CancellationException));
                        long stage1Ms = -1, stage2Ms = -1, totalMs = -1;
                        int successStage = 0;
                        String decodedText = "";

                        if (outcome != null) {
                            stage1Ms = Math.max(-1, Math.round(outcome.stage1Ns / 1_000_000.0));
                            stage2Ms = Math.max(-1, Math.round(outcome.stage2Ns / 1_000_000.0));
                            totalMs  = Math.max(-1, Math.round(outcome.totalNs  / 1_000_000.0));
                            successStage = outcome.successStage;
                            if (outcome.result != null && outcome.result.getText() != null) {
                                decodedText = sanitizeDecoded(outcome.result.getText());
                            }
                        } else {
                            if (ex != null) Log.w(TAG, "decode outcome ex stack:", ex);
                        }

                        // build pixelsPerModule for logging as before...
                        double pixelsPerModule = -1.0;
                        if (successStage > 0 && outcome != null && outcome.result != null) {
                            try { pixelsPerModule = computePixelsPerModuleFromResultPoints(outcome.result, qrVersion); } catch (Exception ignored) {}
                        }
                        if (pixelsPerModule < 0.0) {
                            int mods = 21 + (qrVersion - 1) * 4;
                            pixelsPerModule = (double) finalCropSize / (double) mods;
                        }

                        String aeafStatus = "AE/OFF AF/OFF ";
                        String previewText = decodedText.isEmpty() ? "" : makePreview(decodedText);

                        csvWorker.appendAnalyze(preprocFrameUptime,
                                finalCropSize, finalCropSize,
                                pixelsPerModule, finalAvgLum,
                                aeafStatus, finalSharp, previewText);

                        if (outcome != null && outcome.result != null) {
                            long decodeUptime = System.currentTimeMillis();
                            handleDecodedAsync(decodedText, decodeUptime, preprocFrameUptime, preprocSensorWallMs, preprocSensorAgeMs,stage1Ms, stage2Ms, totalMs,
                                    successStage, timedOut);
                            String resultText = updateResultText(decodedText, preprocFrameUptime, preprocFrameUptime);
                            if (context instanceof MainActivity) ((MainActivity) context).updateResultText(resultText);
                        }
                    } finally {
                        decodeBusy.set(false);
                    }
                });

            });

            preview.setSurfaceProvider(previewView.getSurfaceProvider());
            cameraProvider.unbindAll();
            try {
                cameraProvider.bindToLifecycle((MainActivity) context, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);
                Log.i(TAG, "CameraX bound for decoding (AsyncCallback)");
            } catch (Exception e) {
                Log.e(TAG, "Camera bind failed", e);
                if (context instanceof MainActivity) {
                    ((MainActivity) context).runOnUiThread(() -> Toast.makeText(context, "Camera bind failed", Toast.LENGTH_SHORT).show());
                }
            }
        }, ContextCompat.getMainExecutor(context));
    }



    public void stopCameraXDecoding() {
        if (context instanceof MainActivity) {
            ((MainActivity) context).decoding.set(false);
        }
        previewView.setVisibility(android.view.View.GONE);
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(context);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cp = cameraProviderFuture.get();
                cp.unbindAll();
            } catch (Exception e) {
                Log.w(TAG, "stopCameraXDecoding: unbind failed", e);
            }
        }, ContextCompat.getMainExecutor(context));
    }


    private boolean yuv420ToNv21SafeInto(ImageProxy image, byte[] outNv21) {
        try {
            ImageProxy.PlaneProxy[] planes = image.getPlanes();
            if (planes == null || planes.length < 3) return false;
            int width = image.getWidth();
            int height = image.getHeight();
            int ySize = width * height;
            int chromaSize = ySize / 2;
            if (outNv21.length < ySize + chromaSize) return false;

            ByteBuffer yBuf = planes[0].getBuffer();
            int yRowStride = planes[0].getRowStride();
            int pos = 0;
            byte[] row = new byte[yRowStride];
            yBuf.rewind();
            for (int rowIdx = 0; rowIdx < height; rowIdx++) {
                int toGet = Math.min(yRowStride, yBuf.remaining());
                yBuf.get(row, 0, toGet);
                int copy = Math.min(width, toGet);
                System.arraycopy(row, 0, outNv21, pos, copy);
                pos += copy;
            }

            ByteBuffer uBuf = planes[1].getBuffer();
            ByteBuffer vBuf = planes[2].getBuffer();
            int uRowStride = planes[1].getRowStride();
            int vRowStride = planes[2].getRowStride();
            int uPixelStride = planes[1].getPixelStride();
            int vPixelStride = planes[2].getPixelStride();

            int chromaHeight = height / 2;
            int chromaOut = ySize;
            byte[] uRow = new byte[uRowStride];
            byte[] vRow = new byte[vRowStride];

            for (int rowIdx = 0; rowIdx < chromaHeight; rowIdx++) {
                int uRowStart = rowIdx * uRowStride;
                int vRowStart = rowIdx * vRowStride;
                if (uRowStart < uBuf.capacity()) {
                    uBuf.position(uRowStart);
                    int uToGet = Math.min(uRowStride, uBuf.remaining());
                    uBuf.get(uRow, 0, uToGet);
                }
                if (vRowStart < vBuf.capacity()) {
                    vBuf.position(vRowStart);
                    int vToGet = Math.min(vRowStride, vBuf.remaining());
                    vBuf.get(vRow, 0, vToGet);
                }
                for (int col = 0; col < width; col += 2) {
                    int chromaIndex = col / 2;
                    int uIndex = chromaIndex * uPixelStride;
                    int vIndex = chromaIndex * vPixelStride;

                    int uVal = (uIndex < uRow.length) ? (uRow[uIndex] & 0xFF) : 128;
                    int vVal = (vIndex < vRow.length) ? (vRow[vIndex] & 0xFF) : 128;

                    if (chromaOut + 1 < outNv21.length) {
                        outNv21[chromaOut++] = (byte) vVal;
                        outNv21[chromaOut++] = (byte) uVal;
                    }
                }
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "yuv conversion failed", e);
            return false;
        }
    }

    private void extractYPlaneInto(byte[] nv21, int width, int height, byte[] outY) {
        int ySize = width * height;
        System.arraycopy(nv21, 0, outY, 0, Math.min(ySize, nv21.length));
    }

    private byte[] rotateY90CW(byte[] src, int w, int h) {
        byte[] dst = new byte[src.length];
        for (int r = 0; r < h; r++) {
            int rowOffset = r * w;
            for (int c = 0; c < w; c++) {
                int srcIdx = rowOffset + c;
                int dstIdx = c * h + (h - 1 - r);
                dst[dstIdx] = src[srcIdx];
            }
        }
        return dst;
    }

    private byte[] rotateY90CCW(byte[] src, int w, int h) {
        byte[] dst = new byte[src.length];
        for (int r = 0; r < h; r++) {
            int rowOffset = r * w;
            for (int c = 0; c < w; c++) {
                int srcIdx = rowOffset + c;
                int dstIdx = (w - 1 - c) * h + r;
                dst[dstIdx] = src[srcIdx];
            }
        }
        return dst;
    }

    private double computeAverageLuminanceSampled(byte[] yPlane, int w, int h, int step) {
        if (yPlane == null) return -1.0;
        long sum = 0;
        long cnt = 0;
        for (int r = 0; r < h; r += step) {
            int rowOff = r * w;
            for (int c = 0; c < w; c += step) {
                sum += (yPlane[rowOff + c] & 0xFF);
                cnt++;
            }
        }
        if (cnt == 0) return -1.0;
        return ((double) sum) / ((double) cnt);
    }

    private double computePixelsPerModuleFromResultPoints(Result res, int version) {
        com.google.zxing.ResultPoint[] pts = res.getResultPoints();
        if (pts == null || pts.length < 2) return -1.0;
        double maxDist = 0.0;
        int a = 0, b = 0;
        for (int i = 0; i < pts.length; i++) {
            for (int j = i + 1; j < pts.length; j++) {
                double dx = pts[i].getX() - pts[j].getX();
                double dy = pts[i].getY() - pts[j].getY();
                double d = Math.hypot(dx, dy);
                if (d > maxDist) {
                    maxDist = d;
                    a = i;
                    b = j;
                }
            }
        }
        if (maxDist <= 0.0) return -1.0;
        double ax = pts[a].getX();
        double ay = pts[a].getY();
        double bx = pts[b].getX();
        double by = pts[b].getY();
        double widthPx = Math.hypot(ax - bx, ay - by);
        int modules = 21 + (Math.max(1, version) - 1) * 4;
        if (modules <= 0) return -1.0;
        return widthPx / (double) modules;
    }

    private void handleDecodedAsync(String decodedText, long decodeWallMs, long frameWallMs, long sensorExposureEndMs, long sensorToAppMs,long stage1Ms, long stage2Ms, long totalMs, int successStage, boolean timedOut) {
        logHandleDecoded(decodedText, decodeWallMs, frameWallMs, sensorExposureEndMs, sensorToAppMs,stage1Ms, stage2Ms, totalMs, successStage, timedOut);
    }

    private void logHandleDecoded(String decodedText, long decodeWallMs, long frameWallMs, long sensorExposureEndMs, long sensorToAppMs,long stage1Ms, long stage2Ms, long totalMs, int successStage, boolean timedOut) {
        if (decodedText == null || decodedText.isEmpty()) return;
        String[] parts = decodedText.split("\\|");

        int seq = -1;
        if (parts.length >= 1) {
            try {
                seq = Integer.parseInt(parts[0]);
            } catch (Exception ignored) {
            }
        }

        if (seq < 101 || seq > 600) return;//ハードコーディング

        Boolean prev = decodeLoggedSeqs.putIfAbsent(seq, Boolean.TRUE);
        if (prev != null) return;


        boolean crcOk = false;
        if (parts.length >= 4) {
            String maybeCrcRaw = parts[parts.length - 1];
            String maybeCrc = maybeCrcRaw == null ? "" : maybeCrcRaw.trim().replaceAll("[\\r\\n]", "");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.length - 1; i++) {
                if (i > 0) sb.append("|");
                sb.append(parts[i]);
                String recomputedRaw = crc32Hex(sb.toString());
                String recomputed = recomputedRaw.trim().replaceAll("[\\r\\n]", "");
                crcOk = recomputed.equalsIgnoreCase(maybeCrc);
            }
        }

        long producedWallMs = -1;
        if (parts.length >= 2) {
            try {
                producedWallMs = Long.parseLong(parts[1]);
            } catch (Exception ignored) {
            }
        }
        csvWorker.appendDecode(seq, producedWallMs, sensorExposureEndMs, frameWallMs, decodeWallMs, stage1Ms, stage2Ms, totalMs, successStage, timedOut, crcOk);
    }

    private String sanitizeDecoded(String s) {
        if (s == null) return null;
        return s.replaceAll("[\\u0000-\\u001F\\u007F]", "");
    }

    private double computeTenengradSharpness(byte[] y, int w, int h) {
        long sum = 0;
        int count = 0;
        for (int r = 1; r < h - 1; r++) {
            int row = r * w;
            for (int c = 1; c < w - 1; c++) {
                int idx = row + c;
                int p00 = y[idx - w - 1] & 0xFF;
                int p01 = y[idx - w] & 0xFF;
                int p02 = y[idx - w + 1] & 0xFF;
                int p10 = y[idx - 1] & 0xFF;
                int p12 = y[idx + 1] & 0xFF;
                int p20 = y[idx + w - 1] & 0xFF;
                int p21 = y[idx + w] & 0xFF;
                int p22 = y[idx + w + 1] & 0xFF;
                int gx = (-p00 - 2 * p01 - p02) + (p20 + 2 * p21 + p22);
                int gy = (-p00 - 2 * p10 - p20) + (p02 + 2 * p12 + p22);
                int g2 = gx * gx + gy * gy;
                sum += g2;
                count++;
            }
        }
        if (count == 0) return 0.0;
        return (double) sum / (double) count;
    }

    String crc32Hex(String s) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(s.getBytes());
        return Long.toHexString(crc.getValue()).toUpperCase(Locale.US);
    }

    String updateResultText(String decodedText, long frameTs, long decodeTs) {//プレビューテキスト更新
        String preview = makePreview(decodedText);
        int seq = -1;
        try {
            if (decodedText != null) {
                String[] parts = decodedText.split("\\|");
                if (parts.length >= 1) seq = Integer.parseInt(parts[0]);
            }
        } catch (Exception ignored) {
        }
        final int s = seq;
        return String.format(Locale.US, "seq=%d frame_ts=%d decode_ts=%d\n%s", s, frameTs, decodeTs, preview);
    }

    private String makePreview(String text) {//プレビュー生成
        final int maxLen= 200;
        if (text == null) return "";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...";//残りは...
    }
}
// decode 結果とステージタイミングをまとめて返す
class DecodeOutcome {
    public Result result;
    public long stage1Ns = 0L;   // stage1 実行時間 (ns)
    public long stage2Ns = 0L;   // stage2 実行時間 (ns)
    public long totalNs = 0L;    // 合計実行時間 (ns)
    public int successStage = 0; // 0 = no, 1 = stage1, 2 = stage2
}
