package com.example.myapplication;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.view.PreviewView;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;


@OptIn(markerClass = {androidx.camera.camera2.interop.ExperimentalCamera2Interop.class})
public class MainActivity extends AppCompatActivity {

    // UI
    PreviewView previewView;
    ImageView qrImage;
    Button btnGenerate, btnDecode;
    TextView resultText;

    // Executors
    final ExecutorService genRenderExecutor = Executors.newSingleThreadExecutor(new NamedThreadFactory("gen-render"));
    final ScheduledExecutorService producerScheduler = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("producer-sched"));
    final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor(new NamedThreadFactory("analyze"));
    final ExecutorService logExecutor = Executors.newSingleThreadExecutor(new NamedThreadFactory("log-io"));


    private final ExecutorService decodeExecutor = Executors.newFixedThreadPool(3, new NamedThreadFactory("decode"));

    private final ScheduledExecutorService timeoutScheduler =
            Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("timeout-sched"));



    final AtomicBoolean generating = new AtomicBoolean(false);
    final AtomicBoolean decoding = new AtomicBoolean(false);
    volatile java.util.concurrent.ScheduledFuture<?> producerFuture = null;


    final int qrVersion = 20;                     // ハードコーディング
    final ErrorCorrectionLevel qrEcl = ErrorCorrectionLevel.L;
    final int modulePx =10;                        // granularity
    final int TARGET_HZ = 30;
    // bps / generator frequency (<=30 for this mode)
    final int runID = 11;
    final long offset = 0;


    final AtomicInteger seqCounter = new AtomicInteger(0);
    final CsvWorker csvWorker = new CsvWorker(this, offset);
    final String CSV_FILENAME = "decode_log_"+qrVersion+"_"+qrEcl+"_"+modulePx+"_"+TARGET_HZ+"_"+runID+".csv";
    final String ANALYZE_LOG_FILENAME = "analyze_log_"+qrVersion+"_"+qrEcl+"_"+modulePx+"_"+TARGET_HZ+"_"+runID+".csv";
    final String PRODUCED_CSV_FILENAME = "produced_log_"+qrVersion+"_"+qrEcl+"_"+modulePx+"_"+TARGET_HZ+"_"+runID+".csv";


    final ConcurrentHashMap<Integer, Boolean> decodeLoggedSeqs = new ConcurrentHashMap<>();
    final ConcurrentHashMap<Integer, Boolean> producedLoggedSeqs = new ConcurrentHashMap<>();
    final ConcurrentHashMap<Integer, ProducedMeta> producedMetaMap = new ConcurrentHashMap<>();


    static final Map<com.google.zxing.DecodeHintType, Object> QR_HINTS;
    static {
        EnumMap<com.google.zxing.DecodeHintType, Object> m = new EnumMap<>(com.google.zxing.DecodeHintType.class);
        m.put(com.google.zxing.DecodeHintType.POSSIBLE_FORMATS, java.util.Collections.singletonList(BarcodeFormat.QR_CODE));
        QR_HINTS = java.util.Collections.unmodifiableMap(m);
    }
    static final ThreadLocal<MultiFormatReader> READER_TL = ThreadLocal.withInitial(() -> {
        MultiFormatReader r = new MultiFormatReader();
        r.setHints(QR_HINTS);
        return r;
    });


    static final ThreadLocal<byte[]> NV21_BUF_TL = ThreadLocal.withInitial(() -> null);
    static final ThreadLocal<byte[]> YPLANE_BUF_TL = ThreadLocal.withInitial(() -> null);


    final AtomicBoolean decodeBusy = new AtomicBoolean(false); // for SkipQueue variant
    static final int TARGET_SQUARE = 1080;


    static final long DECODE_TIMEOUT_MS = 100L;

    // misc

    final Handler mainHandler = new Handler(Looper.getMainLooper());
    ActivityResultLauncher<String> requestPermissionLauncher;

    /*version, ecc-> len*/
    private static final int[][] BYTE_CAPACITY_BY_VERSION_AND_ECL = new int[][] {
            /*0*/ {0,0,0,0},
            /*1*/  {17,14,11,7},/*2*/{32,26,20,14},/*3*/{53,42,32,24},/*4*/{78,62,46,34},/*5*/{106,84,60,44},
            /*6*/ {134,106,74,58},/*7*/{154,122,86,64},/*8*/{192,152,108,84},/*9*/{230,180,130,98},/*10*/{271,213,151,119},
            /*11*/{321,251,177,137},/*12*/{367,287,203,155},/*13*/{425,331,241,177},/*14*/{458,362,258,194},/*15*/{520,412,292,220},
            /*16*/{586,450,322,250},/*17*/{644,504,364,280},/*18*/{718,560,394,310},/*19*/{792,624,442,338},/*20*/{858,666,482,382},
            /*21*/{929,711,509,403},/*22*/{1003,779,565,439},/*23*/{1091,857,611,461},/*24*/{1171,911,661,511},/*25*/{1273,997,715,535},
            /*26*/{1367,1059,751,593},/*27*/{1465,1125,805,625},/*28*/{1528,1190,868,658},/*29*/{1628,1264,908,698},/*30*/{1732,1370,982,742},
            /*31*/{1840,1452,1030,790},/*32*/{1952,1538,1112,842},/*33*/{2068,1628,1168,898},/*34*/{2188,1722,1228,958},/*35*/{2303,1809,1283,983},
            /*36*/{2431,1911,1351,1051},/*37*/{2563,1989,1423,1093},/*38*/{2699,2099,1499,1139},/*39*/{2809,2213,1579,1219},/*40*/{2953,2331,1663,1273}
    };

    int eclToIndex(ErrorCorrectionLevel ecl) {
        if (ecl == ErrorCorrectionLevel.L) return 0;
        if (ecl == ErrorCorrectionLevel.M) return 1;
        if (ecl == ErrorCorrectionLevel.Q) return 2;
        return 3; // H
    }
    int estimateMaxEncodableBodyLenLookup(int version, ErrorCorrectionLevel ecl) {
        int v = Math.max(1, Math.min(40, version));
        int idx = eclToIndex(ecl);
        return BYTE_CAPACITY_BY_VERSION_AND_ECL[v][idx];
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        qrImage = findViewById(R.id.qrImage);
        btnGenerate = findViewById(R.id.btnGenerate);
        btnDecode = findViewById(R.id.btnDecode);
        resultText = findViewById(R.id.resultText);


        qrImage.setAdjustViewBounds(false);
        qrImage.setScaleType(ImageView.ScaleType.CENTER);

        try {
            int size = computeQrPixelSize(qrVersion, modulePx);
            qrImage.getLayoutParams().width = size;
            qrImage.getLayoutParams().height = size;
            qrImage.requestLayout();
        } catch (Exception ignored) {}

        csvWorker.start();

        requestPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> { if (!granted) Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show(); }
        );

        Producer producer = new Producer(
                genRenderExecutor,
                producerScheduler,
                seqCounter,
                producedLoggedSeqs,
                producedMetaMap,
                csvWorker,
                mainHandler,
                qrImage,
                qrVersion,
                qrEcl,
                modulePx,
                TARGET_HZ,
                this::estimateMaxEncodableBodyLenLookup
        );

        Decoder decoder = new Decoder(this,
                previewView,
                analysisExecutor,
                decodeExecutor,
                csvWorker,
                decodeLoggedSeqs,
                decodeBusy,
                qrVersion,
                DECODE_TIMEOUT_MS,
                modulePx,
                timeoutScheduler
        );

        btnGenerate.setOnClickListener(v -> {
            boolean now = !generating.get();
            generating.set(now);
            btnGenerate.setText(now ? getString(R.string.generate_stop) : getString(R.string.generate_start));
            if (now) producer.startProducer();
            else producer.stopProducer();
        });

        btnDecode.setOnClickListener(v -> {
            boolean now = !decoding.get();
            decoding.set(now);
            btnDecode.setText(now ? getString(R.string.decode_stop) : getString(R.string.decode_start));
            if (now) decoder.startCameraXDecoding();
            else decoder.stopCameraXDecoding();
        });

    }

    int computeQrPixelSize(int version, int modulePixels) {
        int modules = 21 + (version - 1) * 4;
        return modules * modulePixels;
    }




    public static class ProducedMeta {
        final long producedWallMs;
        final int genHz;
        final String payload;
        ProducedMeta(long producedWallMs, int genHz, String payload) {
            this.producedWallMs = producedWallMs;
            this.genHz = genHz;
            this.payload = payload;
        }
    }


    void updateResultText(String text) {
        if (resultText == null) return;
        runOnUiThread(() -> resultText.setText(text));
    }



    @Override protected void onDestroy() {
        super.onDestroy();
        generating.set(false);
        decoding.set(false);
        try { if (producerFuture != null) producerFuture.cancel(true); } catch (Exception ignored) {}
        producerScheduler.shutdownNow();
        genRenderExecutor.shutdownNow();
        analysisExecutor.shutdownNow();
        logExecutor.shutdownNow();
        timeoutScheduler.shutdownNow();

        try { decodeExecutor.shutdownNow(); } catch (Exception ignored) {}

        for (Map.Entry<Integer, ProducedMeta> e : producedMetaMap.entrySet()) {
            ProducedMeta m = e.getValue();
            if (e.getKey() >= 51 && e.getKey() <= 300) {
                csvWorker.appendProducedFinal(e.getKey(),
                        m.producedWallMs,
                        -1,
                        m.genHz
                );
            }
        }
        producedMetaMap.clear();
        csvWorker.stopAndFlush();
    }

    static class NamedThreadFactory implements java.util.concurrent.ThreadFactory {
        private final String base;
        private final AtomicInteger idx = new AtomicInteger(0);
        NamedThreadFactory(String base) { this.base = base; }
        @Override public Thread newThread(@NonNull Runnable r) {
            Thread t = new Thread(r, base + "-" + idx.getAndIncrement());
            t.setDaemon(true);
            t.setUncaughtExceptionHandler((thread, ex) -> Log.e("NamedThreadFactory", "Uncaught in " + thread.getName(), ex));
            return t;
        }
    }
}
