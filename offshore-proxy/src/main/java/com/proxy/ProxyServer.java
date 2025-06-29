package com.proxy;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.Base64;
import org.json.JSONObject;

public class ProxyServer {
    public static void main(String[] args) throws IOException {
        final int PORT = 9000;
        ServerSocket serverSocket = new ServerSocket(PORT);
        System.out.println("Offshore proxy listening on port: " + PORT);

        while (true) {
            Socket clientSocket = serverSocket.accept();
            System.out.println("Connected to Ship Proxy");
            new Thread(() -> handleConnection(clientSocket)).start();
        }
    }

    private static void handleConnection(Socket shipSocket) {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(shipSocket.getInputStream()));
            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(shipSocket.getOutputStream()));

            String line;
            while ((line = in.readLine()) != null) {
                JSONObject request = new JSONObject(line);
                String method = request.getString("method");

                if ("CONNECT".equalsIgnoreCase(method)) {
                    handleConnect(request, shipSocket);
                    break;
                } else {
                    String response = handleHttpRequest(request);
                    out.write(response + "\n");
                    out.flush();
                }
            }
        } catch (IOException e) {
            System.err.println("Connection error: " + e.getMessage());
        } finally {
            try {
                shipSocket.close();
            } catch (IOException e) {
                System.err.println("Error closing ship socket: " + e.getMessage());
            }
        }
    }

    private static void handleConnect(JSONObject req, Socket shipSocket) {
        String host = req.getString("hostname");
        int port = req.getInt("port");

        Socket targetSocket = null;
        try {

            OutputStream shipOut = shipSocket.getOutputStream();
            shipOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes());
            shipOut.flush();


            targetSocket = new Socket(host, port);
            // Start bidirectional data forwarding
            Socket finalTargetSocket = targetSocket;
            Thread t1 = new Thread(() -> {
                try {
                    pipeData(shipSocket.getInputStream(), finalTargetSocket.getOutputStream(), "Ship->Target");
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            Thread t2 = new Thread(() -> {
                try {
                    pipeData(finalTargetSocket.getInputStream(), shipSocket.getOutputStream(), "Target->Ship");
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            t1.start();
            t2.start();

            // Wait for both threads to complete
            t1.join();
            t2.join();

        } catch (IOException io) {
            System.out.println("Connection error: " + io.getMessage());
        } catch (Exception e) {
            System.err.println("CONNECT error: " + e.getMessage());
            try {
                OutputStream shipOut = shipSocket.getOutputStream();
                shipOut.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".getBytes());
                shipOut.flush();
            } catch (IOException ignore) {}
        } finally {
            // Clean up resources
            if (targetSocket != null) {
                try {
                    targetSocket.close();
                } catch (IOException e) {
                    System.err.println("Error closing target socket: " + e.getMessage());
                }
            }
            try {
                shipSocket.close();
            } catch (IOException e) {
                System.err.println("Error closing ship socket: " + e.getMessage());
            }
        }
    }

    private static void pipeData(InputStream in, OutputStream out, String direction) {
        try {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                System.out.println("Forwarding " + read + " bytes (" + direction + ")");
                out.write(buffer, 0, read);
                out.flush();
            }
        } catch (Exception e) {
            System.err.println("Pipe error (" + direction + "): " + e.getMessage());
        } finally {
            try {
                out.close();
            } catch (IOException e) {
                System.err.println("Error closing output stream: " + e.getMessage());
            }
            try {
                in.close();
            } catch (IOException e) {
                System.err.println("Error closing input stream: " + e.getMessage());
            }
        }
    }

    private static String handleHttpRequest(JSONObject requestJson) throws IOException {
        String method = requestJson.getString("method");
        String protocol = requestJson.getString("protocol");
        String hostname = requestJson.getString("hostname");
        int port = requestJson.getInt("port");
        String path = requestJson.getString("path");
        String body = requestJson.optString("body", "");
        JSONObject headers = requestJson.getJSONObject("headers");

        String urlStr = protocol + "://" + hostname + ":" + port + path;
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        conn.setRequestMethod(method);
        for (String key : headers.keySet()) {
            conn.setRequestProperty(key, headers.getString(key));
        }

        if (!body.isEmpty()) {
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes());
            }
        }

        int status = conn.getResponseCode();
        InputStream inputStream = status < 400 ? conn.getInputStream() : conn.getErrorStream();

        ByteArrayOutputStream resultStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int length;
        while ((length = inputStream.read(buffer)) != -1) {
            resultStream.write(buffer, 0, length);
        }

        JSONObject responseJson = new JSONObject();
        responseJson.put("statusCode", status);

        JSONObject responseHeaders = new JSONObject();
        for (Map.Entry<String, List<String>> entry : conn.getHeaderFields().entrySet()) {
            if (entry.getKey() != null && !entry.getValue().isEmpty()) {
                responseHeaders.put(entry.getKey(), entry.getValue().get(0));
            }
        }
        responseJson.put("headers", responseHeaders);
        responseJson.put("body", Base64.getEncoder().encodeToString(resultStream.toByteArray()));

        return responseJson.toString();
    }
}
