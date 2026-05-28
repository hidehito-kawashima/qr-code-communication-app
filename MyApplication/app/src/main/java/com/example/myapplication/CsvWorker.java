package com.example.myapplication;

import android.content.Context;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.util.Locale;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;


public class CsvWorker {
    private static final String TAG = "CsvWorker";
    private final BlockingDeque<String> decodeQueue = new LinkedBlockingDeque<>();
    private final BlockingDeque<String> analyzeQueue = new LinkedBlockingDeque<>();
    private final BlockingDeque<String> producedFinalQueue = new LinkedBlockingDeque<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread worker;
    private final Context context;
    private final long offset;

    public CsvWorker(Context context, long offset) {
        this.context = context;
        this.offset = offset;
    }

    void start() {
        if (!running.get()) {
            try {
                File decodeFile = new File(context.getFilesDir(), ((MainActivity)context).CSV_FILENAME);
                if (decodeFile.exists()) { boolean ok = decodeFile.delete(); Log.i(TAG, "Deleted existing decode CSV on start: " + ok); }
                File analyzeFile = new File(context.getFilesDir(), ((MainActivity)context).ANALYZE_LOG_FILENAME);
                if (analyzeFile.exists()) { boolean ok2 = analyzeFile.delete(); Log.i(TAG, "Deleted existing analyze CSV on start: " + ok2); }
                File producedFile = new File(context.getFilesDir(), ((MainActivity)context).PRODUCED_CSV_FILENAME);
                if (producedFile.exists()) { boolean ok3 = producedFile.delete(); Log.i(TAG, "Deleted existing produced CSV on start: " + ok3); }
            } catch (Exception e) { Log.w(TAG, "Failed to delete existing CSV on start", e); }
        }

        if (!running.getAndSet(true)) {
            worker = new Thread(this::loop, "csv-writer");
            worker.setDaemon(true);
            worker.start();
        }
    }

    void stopAndFlush() {
        running.set(false);
        if (worker != null) {
            worker.interrupt();
            try { worker.join(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        flushQueueOnce(producedFinalQueue, ((MainActivity)context).PRODUCED_CSV_FILENAME, "seq,produced_time_ms,displayed_time_ms,displayed_minus_produced_ms,current_framerate\n");
        flushQueueOnce(decodeQueue, ((MainActivity)context).CSV_FILENAME, "seq,produced_time_ms,sensor_exposure_end_ms,frame_time_ms,decode_time_ms,prod-sensor_ms,sensor-frame_ms,frame-decode_ms,prod-decode_ms,step1ms,step2ms,totalms,successStage,timedOut,crcOk\n");
        flushQueueOnce(analyzeQueue, ((MainActivity)context).ANALYZE_LOG_FILENAME, "analysis_time_ms,res_width,res_height,pixels_per_module,avg_luminance,AE_AF_status,sharpness,payload_preview\n");
    }

    //生成ログ
    void appendProducedFinal(int seq, long producedWallMs, long displayedWallMs, int currentHz) {
        long producedMs = producedWallMs+offset;
        long displayedMs = displayedWallMs+offset;
        String line = String.format(java.util.Locale.US, "%d,%d,%d,%d,%d\n",//seq, 生成時刻, 表示時刻, 生成表示間
                seq,
                (producedMs > 0 ? producedMs : -1),
                (displayedMs > 0 ? displayedMs : -1),
                ((displayedMs > 0 && producedMs > 0) ? (displayedMs - producedMs) : -1),
                currentHz);
        if (!running.get()) start();
        producedFinalQueue.offer(line);
    }

    //デコードログ
    void appendDecode(int seq, long producedWallMs, long sensorExposureEndMs, long frameWallMs, long decodeWallMs,long stage1Ms, long stage2Ms, long totalMs,int successStage,boolean timedOut, boolean crcOk) { long sensorMs = sensorExposureEndMs;
        long producedMs = producedWallMs+offset;//時刻補正
        long diff_prod_sensor = (producedMs > 0 && sensorMs > 0) ? (sensorMs - producedMs) : -1;
        long diff_sensor_frame = (sensorMs > 0 && frameWallMs > 0) ? (frameWallMs - sensorMs) : -1;
        long diff_frame_decode = (frameWallMs > 0 && decodeWallMs > 0) ? (decodeWallMs - frameWallMs) : -1;
        long diff_prod_decode = (producedMs > 0 && decodeWallMs > 0) ? (decodeWallMs - producedMs) : -1;

        final String line = String.format(Locale.US, "%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%b,%b\n",//seq, 生成時刻, 露光時刻, 撮影時刻, デコード時刻, 生成露光間, 露光撮影間, 撮影デコード間,生成デコード間, stage1, stage2, total, stage数, timeout, crc
                seq,
                (producedMs > 0 ? producedMs : -1),
                (sensorMs > 0 ? sensorMs : -1),
                (frameWallMs > 0 ? frameWallMs : -1),
                (decodeWallMs > 0 ? decodeWallMs : -1),
                (diff_prod_sensor > 0 ? diff_prod_sensor : -1),
                (diff_sensor_frame > 0 ? diff_sensor_frame : -1),
                (diff_frame_decode > 0 ? diff_frame_decode : -1),
                (diff_prod_decode > 0 ? diff_prod_decode : -1),
                stage1Ms,
                stage2Ms,
                totalMs,
                successStage,
                timedOut,
                crcOk
        );
        if (!running.get()) start(); decodeQueue.offer(line); }

    //フレームログ
    void appendAnalyze(long tsMs,int width, int height, double pixelsPerModule, double avgLum, String aeaf, double sharpnes,String text) {
        String safe = text == null ? "" : text;
        String esc = "\"" + safe.replace("\"", "\"\"") + "\"";
        String line = String.format(java.util.Locale.US, "%d,%d,%d,%.3f,%.3f,%s,%.3f,%s\n", tsMs, width, height, pixelsPerModule, avgLum, aeaf, sharpnes,esc);//時刻, はば, 高さ, moduleあたりpixel数, 照度, 露光フォーカス, 滑らかさ, 結果
        if (!running.get()) start();
        analyzeQueue.offer(line);
    }

    private void loop() {
        try {
            while (running.get()) {
                String item = producedFinalQueue.poll(200, TimeUnit.MILLISECONDS);
                if (item != null) producedFinalQueue.offerFirst(item);

                item = decodeQueue.poll(0, TimeUnit.MILLISECONDS);
                if (item != null) decodeQueue.offerFirst(item);

                item = analyzeQueue.poll(0, TimeUnit.MILLISECONDS);
                if (item != null) analyzeQueue.offerFirst(item);

                flushQueueOnce(producedFinalQueue, ((MainActivity)context).PRODUCED_CSV_FILENAME, "seq,produced_time_ms,displayed_time_ms,displayed_minus_produced_ms,current_framerate\n");
                flushQueueOnce(decodeQueue, ((MainActivity)context).CSV_FILENAME, "seq,produced_time_ms,sensor_exposure_end_ms,frame_time_ms,decode_time_ms,prod-sensor_ms,sensor-frame_ms,frame-decode_ms,prod-decode_ms,step1ms,step2ms,totalms,successStage,timedOut,crcOk\n");
                flushQueueOnce(analyzeQueue, ((MainActivity)context).ANALYZE_LOG_FILENAME, "analysis_time_ms,res_width,res_height,pixels_per_module,avg_luminance,AE_AF_status,sharpness,payload_preview\n");
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } finally {
            flushQueueOnce(producedFinalQueue, ((MainActivity)context).PRODUCED_CSV_FILENAME, "seq,produced_time_ms,displayed_time_ms,displayed_minus_produced_ms,current_framerate\n");
            flushQueueOnce(decodeQueue, ((MainActivity)context).CSV_FILENAME, "seq,produced_time_ms,sensor_exposure_end_ms,frame_time_ms,decode_time_ms,prod-sensor_ms,sensor-frame_ms,frame-decode_ms,prod-decode_ms,step1ms,step2ms,totalms,successStage,timedOut,crcOk\n");
            flushQueueOnce(analyzeQueue, ((MainActivity)context).ANALYZE_LOG_FILENAME, "analysis_time_ms,res_width,res_height,pixels_per_module,avg_luminance,AE_AF_status,sharpness,payload_preview\n");
        }
    }

    private void flushQueueOnce(BlockingDeque<String> q, String fname, String header) {
        if (q.isEmpty()) return;
        File file = new File(context.getFilesDir(), fname);
        try {
            long MAX_FILE_BYTES = 5 * 1024 * 1024L;
            if (file.exists() && file.length() > MAX_FILE_BYTES) rotateFile(file);
            boolean newFile = !file.exists();
            try (FileOutputStream fos = new FileOutputStream(file, true);
                 BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(fos))) {
                if (newFile) bw.write(header);
                String line;
                int drained = 0;
                while ((line = q.poll()) != null && drained < 1000) {
                    bw.write(line);
                    drained++;
                }
                bw.flush();
            }
        } catch (Exception e) {
            Log.e(TAG, "CSV write failed for " + fname, e);
        }
    }

    private void rotateFile(File file) {//ファイルロギング
        try {
            String bakName = file.getName() + "." + System.currentTimeMillis();
            File bak = new File(file.getParentFile(), bakName);
            if (!file.renameTo(bak)) {
                try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) { raf.setLength(0); } catch (Exception ignored) {}
            }
        } catch (Exception e) { Log.e(TAG, "rotate failed", e); }
    }

}
