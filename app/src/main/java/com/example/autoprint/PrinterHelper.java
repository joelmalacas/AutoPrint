package com.example.autoprint;

import android.graphics.Bitmap;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public class PrinterHelper {

    private static final String TAG = "AutoPrintDebug";

    private static final String[] CANDIDATE_PATHS = {
            "/ipp/print",
            "/ipp/printer",
            "/ipp"
    };

    // Formatos confirmados pelo ipptool na HP M139-M142
    private static final String[] VALID_FORMATS = {
            "application/PCLm",
            "image/pwg-raster",
            "application/octet-stream"
    };

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 15000;
    private static final int JPEG_QUALITY = 90;
    private static final int MAX_WIDTH_PX = 1600;

    public interface PrintCallback {
        void onSuccess();
        void onError(String message);
    }

    public static void printImage(String ip, int port, Bitmap bitmap, PrintCallback callback) {
        new Thread(() -> {
            try {
                Bitmap scaled = scaleDown(bitmap);
                byte[] jpegBytes = bitmapToJpeg(scaled);

                StringBuilder attemptsLog = new StringBuilder();

                // Tenta os formatos reais reportados pela impressora
                for (String format : VALID_FORMATS) {
                    for (String path : CANDIDATE_PATHS) {
                        try {
                            Log.d(TAG, "Enviando para " + path + " no formato " + format);
                            String result = sendIppPrintJob(ip, port, path, format, jpegBytes);

                            if (result == null) {
                                Log.d(TAG, "Sucesso no envio via IPP (" + path + " - " + format + ")!");
                                if (callback != null) callback.onSuccess();
                                return;
                            }
                            attemptsLog.append("IPP ").append(path).append(" [").append(format).append("] -> ").append(result).append("\n");
                        } catch (IOException e) {
                            attemptsLog.append("IPP ").append(path).append(" [").append(format).append("] -> ")
                                    .append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append("\n");
                        }
                    }
                }

                if (callback != null) {
                    callback.onError("Falha no envio IPP:\n" + attemptsLog);
                }

            } catch (Exception e) {
                Log.e(TAG, "Erro interno", e);
                if (callback != null) callback.onError("Erro interno: " + e.getMessage());
            }
        }).start();
    }

    private static Bitmap scaleDown(Bitmap original) {
        if (original.getWidth() <= MAX_WIDTH_PX) return original;
        float ratio = (float) MAX_WIDTH_PX / original.getWidth();
        int targetHeight = Math.round(original.getHeight() * ratio);
        return Bitmap.createScaledBitmap(original, MAX_WIDTH_PX, targetHeight, true);
    }

    private static byte[] bitmapToJpeg(Bitmap bitmap) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos);
        return baos.toByteArray();
    }

    private static String sendIppPrintJob(String ip, int port, String path, String documentFormat, byte[] documentBytes) throws IOException {
        byte[] ippHeader = buildIppPrintJobHeader(ip, port, path, documentFormat);

        byte[] fullPayload = new byte[ippHeader.length + documentBytes.length];
        System.arraycopy(ippHeader, 0, fullPayload, 0, ippHeader.length);
        System.arraycopy(documentBytes, 0, fullPayload, ippHeader.length, documentBytes.length);

        URL url = new URL("http://" + ip + ":" + port + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Content-Type", "application/ipp");
            conn.setFixedLengthStreamingMode(fullPayload.length);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(fullPayload);
                os.flush();
            }

            int httpStatus = conn.getResponseCode();
            if (httpStatus != HttpURLConnection.HTTP_OK) {
                return "HTTP " + httpStatus;
            }

            byte[] response = readAll(conn.getInputStream());
            int ippStatus = ippStatusCode(response);
            if (ippStatus <= 0x00FF) {
                return null; // Sucesso IPP (0x0000 - successful-ok)
            }
            return "HTTP 200 mas IPP Status: 0x" + Integer.toHexString(ippStatus);
        } finally {
            conn.disconnect();
        }
    }

    private static int ippStatusCode(byte[] response) {
        if (response.length < 4) return -1;
        return ((response[2] & 0xFF) << 8) | (response[3] & 0xFF);
    }

    private static byte[] readAll(java.io.InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private static byte[] buildIppPrintJobHeader(String ip, int port, String path, String documentFormat) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        buffer.write(0x02); // Versão IPP 2.0
        buffer.write(0x00);

        buffer.write(0x00); // Operation: Print-Job
        buffer.write(0x02);

        buffer.write(0x00); // Request ID
        buffer.write(0x00);
        buffer.write(0x00);
        buffer.write(0x01);

        buffer.write(0x01); // operation-attributes-tag

        writeAttribute(buffer, 0x47, "attributes-charset", "utf-8");
        writeAttribute(buffer, 0x48, "attributes-natural-language", "en-us");
        writeAttribute(buffer, 0x45, "printer-uri", "ipp://" + ip + ":" + port + path);
        writeAttribute(buffer, 0x42, "requesting-user-name", "AutoPrint");
        writeAttribute(buffer, 0x41, "job-name", "Foto AutoPrint");
        writeAttribute(buffer, 0x49, "document-format", documentFormat);

        buffer.write(0x03); // end-of-attributes-tag

        return buffer.toByteArray();
    }

    private static void writeAttribute(ByteArrayOutputStream buffer, int tag, String name, String value) throws IOException {
        byte[] nameBytes = name.getBytes(StandardCharsets.US_ASCII);
        byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);

        buffer.write(tag);
        writeShort(buffer, nameBytes.length);
        buffer.write(nameBytes);
        writeShort(buffer, valueBytes.length);
        buffer.write(valueBytes);
    }

    private static void writeShort(ByteArrayOutputStream buffer, int value) {
        buffer.write((value >> 8) & 0xFF);
        buffer.write(value & 0xFF);
    }
}