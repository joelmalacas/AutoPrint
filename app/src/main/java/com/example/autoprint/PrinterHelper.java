package com.example.autoprint;

import android.graphics.Bitmap;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Envia a fotografia para impressão através da API local de impressão Windows
 * (script Python com FastAPI + win32print), em vez de falar diretamente com a
 * impressora. A API corre no PC Windows ligado à impressora (ex: Epson M1120) e
 * usa o driver real do Windows para imprimir — por isso não precisamos de saber
 * IPP, ESC/P, ou qualquer protocolo específico da impressora.
 *
 * Endpoint usado: POST http://{ip}:{port}/print/file
 *   - multipart/form-data, campo "file" com a imagem em JPEG
 *   - parâmetro opcional na query string "printer_name" (nome exato da impressora
 *     no Windows; se vazio, a API usa a impressora predefinida do sistema)
 */
public class PrinterHelper {

    private static final String TAG = "AutoPrintDebug";

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 20000;

    private static final int JPEG_QUALITY = 90;
    private static final int MAX_WIDTH_PX = 1600;

    private static final String BOUNDARY = "----AutoPrintBoundary7d1a4f";

    public interface PrintCallback {
        void onSuccess();
        void onError(String message);
    }

    /**
     * @param ip          IP do PC Windows onde a API de impressão está a correr
     * @param port        porta da API (por defeito 8000, conforme o script uvicorn)
     * @param bitmap      imagem já composta (foto + template) a imprimir
     * @param printerName nome exato da impressora no Windows, ou "" para usar a predefinida
     */
    public static void printImage(String ip, int port, Bitmap bitmap, String printerName, PrintCallback callback) {
        try {
            Bitmap scaled = scaleDown(bitmap);
            byte[] jpegBytes = bitmapToJpeg(scaled);

            String path = "/print/file";
            if (printerName != null && !printerName.trim().isEmpty()) {
                path += "?printer_name=" + URLEncoder.encode(printerName.trim(), "UTF-8");
            }

            URL url = new URL("http://" + ip + ":" + port + path);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            try {
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + BOUNDARY);

                byte[] body = buildMultipartBody(jpegBytes);
                conn.setFixedLengthStreamingMode(body.length);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body);
                    os.flush();
                }

                int status = conn.getResponseCode();
                String responseText = readAll(status == HttpURLConnection.HTTP_OK
                        ? conn.getInputStream() : conn.getErrorStream());

                Log.d(TAG, "Resposta da API de impressão (" + status + "): " + responseText);

                if (status == HttpURLConnection.HTTP_OK) {
                    if (callback != null) callback.onSuccess();
                } else {
                    if (callback != null) callback.onError("HTTP " + status + ": " + truncate(responseText, 200));
                }
            } finally {
                conn.disconnect();
            }

        } catch (Exception e) {
            Log.e(TAG, "Erro ao contactar a API de impressão", e);
            if (callback != null) callback.onError(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // ================= PREPARAÇÃO DA IMAGEM =================

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

    // ================= CORPO MULTIPART/FORM-DATA =================

    // Monta manualmente o corpo multipart, equivalente ao que o parâmetro
    // "file: UploadFile = File(...)" da API espera receber
    private static byte[] buildMultipartBody(byte[] jpegBytes) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();

        writeAscii(body, "--" + BOUNDARY + "\r\n");
        writeAscii(body, "Content-Disposition: form-data; name=\"file\"; filename=\"foto.jpg\"\r\n");
        writeAscii(body, "Content-Type: image/jpeg\r\n\r\n");
        body.write(jpegBytes);
        writeAscii(body, "\r\n--" + BOUNDARY + "--\r\n");

        return body.toByteArray();
    }

    private static void writeAscii(ByteArrayOutputStream out, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        out.write(bytes, 0, bytes.length);
    }

    private static String readAll(java.io.InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toString("UTF-8");
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}