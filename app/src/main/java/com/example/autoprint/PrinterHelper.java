package com.example.autoprint;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Envia uma fotografia para impressão usando IPP (Internet Printing Protocol),
 * o protocolo standard usado por impressoras de rede modernas (incluindo todas
 * as compatíveis com AirPrint/HP ePrint), normalmente disponível na porta 631.
 *
 * Ao contrário do ESC/POS (que só serve para impressoras térmicas de talões),
 * o IPP é o que uma impressora HP/Canon/Epson multifunções normal entende.
 */
public class PrinterHelper {

    // Caminhos comuns usados por diferentes impressoras/servidores IPP.
    // Tenta cada um até um responder com sucesso.
    private static final String[] CANDIDATE_PATHS = {
            "/ipp/printer",
            "/ipp/print",
            "/ipp/print/",
            "/ipp",
            "/printer",
            "/"
    };

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 15000;

    // Qualidade JPEG usada ao comprimir a foto antes de enviar (0-100)
    private static final int JPEG_QUALITY = 90;

    // Reduz a foto a uma largura razoável antes de imprimir, para o envio ser rápido
    private static final int MAX_WIDTH_PX = 1600;

    public interface PrintCallback {
        void onSuccess();
        void onError(String message);
    }

    // A impressora reportou "JPEG Unsupported" — por isso tentamos primeiro um PDF simples
    // que contém a foto embutida, já que quase todas as impressoras de rede aceitam PDF.
    private static final String[] CANDIDATE_FORMATS = {
            "application/pdf", "application/octet-stream", "image/jpeg"
    };

    public static void printImage(String ip, int port, Bitmap bitmap, PrintCallback callback) {
        // 1. Reduz o bitmap para uma resolução adequada antes de comprimir (evita OutOfMemory)
        Bitmap scaled = scaleDown(bitmap);

        // 2. Converte para JPEG comprimido
        byte[] jpegBytes = bitmapToJpeg(scaled);

        // 3. Gera o PDF A4 estático (595x842 pt) contendo o JPEG ajustado à folha
        byte[] pdfBytes = buildA4PdfWithJpeg(jpegBytes);

        StringBuilder attemptsLog = new StringBuilder();

        for (String format : CANDIDATE_FORMATS) {
            byte[] payload = format.equals("application/pdf") ? pdfBytes : jpegBytes;

            for (String path : CANDIDATE_PATHS) {
                try {
                    String result = sendIppPrintJob(ip, port, path, format, payload);
                    if (result == null) {
                        if (callback != null) callback.onSuccess();
                        return;
                    }
                    attemptsLog.append(path).append(" [").append(format).append("] -> ")
                            .append(result).append("\n");
                } catch (IOException e) {
                    attemptsLog.append(path).append(" [").append(format).append("] -> ")
                            .append(e.getClass().getSimpleName())
                            .append(": ").append(e.getMessage()).append("\n");
                }
            }
        }

        if (callback != null) {
            callback.onError("Nenhuma combinação respondeu com sucesso:\n" + attemptsLog);
        }
    }

    // ================= PREPARAÇÃO DA IMAGEM =================

    /**
     * Constrói um PDF A4 standard (595 x 842 pt) contendo o JPEG ajustado a 100% da página.
     * Funciona em praticamente todas as impressoras de rede via IPP sem estourar memória.
     */
    private static byte[] buildA4PdfWithJpeg(byte[] jpegBytes) {
        ByteArrayOutputStream pdf = new ByteArrayOutputStream();
        java.util.List<Integer> offsets = new java.util.ArrayList<>();

        // Dimensões ISO A4 estáticas em pontos PDF (72 pt/polegada)
        float pageWidthPt = 595f;
        float pageHeightPt = 842f;

        // Descodifica apenas as dimensões da imagem (sem carregar na memória)
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length, options);
        int widthPx = options.outWidth;
        int heightPx = options.outHeight;

        writeAsciiPdf(pdf, "%PDF-1.4\n");

        // Objeto 1: Catálogo
        offsets.add(pdf.size());
        writeAsciiPdf(pdf, "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");

        // Objeto 2: Páginas
        offsets.add(pdf.size());
        writeAsciiPdf(pdf, "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n");

        // Objeto 3: Definição da Página A4 Estática (595 x 842 pt)
        offsets.add(pdf.size());
        writeAsciiPdf(pdf, "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 "
                + pageWidthPt + " " + pageHeightPt
                + "] /Resources << /XObject << /Im0 4 0 R >> >> /Contents 5 0 R >>\nendobj\n");

        // Objeto 4: Imagem JPEG embutida (stream leve)
        offsets.add(pdf.size());
        writeAsciiPdf(pdf, "4 0 obj\n<< /Type /XObject /Subtype /Image /Width " + widthPx
                + " /Height " + heightPx
                + " /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length "
                + jpegBytes.length + " >>\nstream\n");
        pdf.write(jpegBytes, 0, jpegBytes.length);
        writeAsciiPdf(pdf, "\nendstream\nendobj\n");

        // Objeto 5: Escala a imagem para preencher exatamente a folha A4 (595x842 pt)
        offsets.add(pdf.size());
        String content = "q\n" + pageWidthPt + " 0 0 " + pageHeightPt + " 0 0 cm\n/Im0 Do\nQ";
        byte[] contentBytes = content.getBytes(StandardCharsets.US_ASCII);
        writeAsciiPdf(pdf, "5 0 obj\n<< /Length " + contentBytes.length + " >>\nstream\n");
        pdf.write(contentBytes, 0, contentBytes.length);
        writeAsciiPdf(pdf, "\nendstream\nendobj\n");

        // Tabela xref
        int xrefOffset = pdf.size();
        writeAsciiPdf(pdf, "xref\n0 6\n0000000000 65535 f \n");
        for (int off : offsets) {
            writeAsciiPdf(pdf, String.format(Locale.US, "%010d 00000 n \n", off));
        }

        writeAsciiPdf(pdf, "trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n" + xrefOffset + "\n%%EOF");

        return pdf.toByteArray();
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

    private static void writeAsciiPdf(ByteArrayOutputStream buffer, String text) {
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        buffer.write(bytes, 0, bytes.length);
    }

    // ================= PEDIDO IPP =================
    // Devolve null se o pedido foi bem-sucedido, ou uma descrição do problema caso contrário
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
                // Lê o corpo do erro, se existir, para dar mais pista sobre a causa
                String errorBody = "";
                try {
                    byte[] errBytes = readAll(conn.getErrorStream());
                    errorBody = new String(errBytes, StandardCharsets.UTF_8).trim();
                } catch (Exception ignored) { }
                return "HTTP " + httpStatus
                        + (errorBody.isEmpty() ? "" : " (" + truncate(errorBody, 120) + ")");
            }

            byte[] response = readAll(conn.getInputStream());
            int ippStatus = ippStatusCode(response);
            if (ippStatus <= 0x00FF) {
                return null; // sucesso
            }
            return "HTTP 200 mas estado IPP 0x" + Integer.toHexString(ippStatus)
                    + " (pedido rejeitado pela impressora)";
        } finally {
            conn.disconnect();
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
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

    // ================= CONSTRUÇÃO DO CABEÇALHO IPP (Print-Job) =================

    private static byte[] buildIppPrintJobHeader(String ip, int port, String path, String documentFormat) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        // 1. Versão IPP 1.1
        buffer.write(0x01);
        buffer.write(0x01);

        // 2. operation-id: Print-Job (0x0002)
        buffer.write(0x00);
        buffer.write(0x02);

        // 3. request-id: 1 (4 bytes)
        buffer.write(0x00);
        buffer.write(0x00);
        buffer.write(0x00);
        buffer.write(0x01);

        // 4. Início do grupo de atributos da operação
        buffer.write(0x01); // operation-attributes-tag

        // 5. Atributos Obrigatórios (Strict IPP Specification Order)
        // charset e natural-language TÊM DE SER os dois primeiros charset (0x47)
        writeAttribute(buffer, 0x47, "attributes-charset", "utf-8");
        // naturalLanguage (0x48)
        writeAttribute(buffer, 0x48, "attributes-natural-language", "en-us");

        // printer-uri precisa do esquema http:// ou ipp:// exato
        String uri = "ipp://" + ip + ":" + port + path;
        // uri (0x45)
        writeAttribute(buffer, 0x45, "printer-uri", uri);

        // nameWithoutLanguage (0x42)
        writeAttribute(buffer, 0x42, "requesting-user-name", "AutoPrint");
        writeAttribute(buffer, 0x42, "job-name", "AutoPrint Photo");

        // ipp-attribute-fidelity é OBRIGATÓRIO para a HP não dar 0x0400 (boolean tag 0x22)
        writeBooleanAttribute(buffer, "ipp-attribute-fidelity", false);

        // mimeMediaType (0x49)
        writeAttribute(buffer, 0x49, "document-format", documentFormat);

        // 6. Fim do grupo de atributos
        buffer.write(0x03); // end-of-attributes-tag

        return buffer.toByteArray();
    }

    // Escreve um atributo booleano no formato IPP (Tag 0x22)
    private static void writeBooleanAttribute(ByteArrayOutputStream buffer, String name, boolean value) throws IOException {
        byte[] nameBytes = name.getBytes(StandardCharsets.US_ASCII);
        buffer.write(0x22); // boolean tag
        writeShort(buffer, nameBytes.length);
        buffer.write(nameBytes);
        writeShort(buffer, 1); // valor do booleano tem sempre tamanho 1
        buffer.write(value ? 0x01 : 0x00);
    }

    // Escreve um atributo IPP no formato: tag(1) + tamanho-nome(2) + nome + tamanho-valor(2) + valor
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