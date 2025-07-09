package com.ship;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.net.*;
import java.util.Base64;
import java.util.concurrent.*;

public class Ship {
    private static final String OFFSHORE_HOST = "offshore-proxy";
    private static final int OFFSHORE_PORT = 9000;
    private static final int LISTEN_PORT = 8080;
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final BlockingQueue<RequestWrapper> requestQueue = new LinkedBlockingQueue<>();
    private static final Object connectLock = new Object();

    public static void main(String[] args) throws IOException {
        // Start persistent offshore connection thread
        new Thread(Ship::offshoreWorker).start();

        ServerSocket serverSocket = new ServerSocket(LISTEN_PORT);
        System.out.println("Ship Proxy listening on port " + LISTEN_PORT);

        while (true) {
            Socket clientSocket = serverSocket.accept();
            new Thread(() -> handleClient(clientSocket)).start();
        }
    }

    private static void offshoreWorker() {
        Socket offshoreSocket = null;
        int attempts = 0;

        while (offshoreSocket == null && attempts < 10) {
            try {
                offshoreSocket = new Socket(OFFSHORE_HOST, OFFSHORE_PORT);
            } catch (IOException e) {
                attempts++;
                System.out.println("Waiting for offshore proxy... attempt " + attempts);
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ignored) {}
            }
        }

        if (offshoreSocket == null) {
            System.err.println("Failed to connect to offshore proxy after 10 attempts.");
            return;
        }

        try (
                Socket socket = offshoreSocket;
                BufferedWriter offshoreOut = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
                BufferedReader offshoreIn = new BufferedReader(new InputStreamReader(socket.getInputStream()))
        ) {
            System.out.println("Connected to offshore proxy");

            while (true) {
                RequestWrapper wrapper = requestQueue.take();

                if ("CONNECT".equals(wrapper.method)) {
                    // Handle CONNECT differently - don't use the persistent connection
                    handleConnectRequest(wrapper);
                } else {
                    offshoreOut.write(wrapper.json + "\n");
                    offshoreOut.flush();
                    String response = offshoreIn.readLine();
                    wrapper.future.complete(response);
                }
            }
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }

    private static void handleConnectRequest(RequestWrapper wrapper) {
        try {
            // Create a new connection for CONNECT requests
            Socket offshoreSocket = new Socket(OFFSHORE_HOST, OFFSHORE_PORT);
            BufferedWriter offshoreOut = new BufferedWriter(new OutputStreamWriter(offshoreSocket.getOutputStream()));
            BufferedReader offshoreIn = new BufferedReader(new InputStreamReader(offshoreSocket.getInputStream()));

            offshoreOut.write(wrapper.json + "\n");
            offshoreOut.flush();

            String response = offshoreIn.readLine();
            wrapper.future.complete(response);

            // If successful, start tunneling
            if (response != null && response.contains("200")) {
                // The tunneling will be handled in handleConnect method
                wrapper.offshoreSocket = offshoreSocket;
            } else {
                offshoreSocket.close();
            }
        } catch (IOException e) {
            wrapper.future.completeExceptionally(e);
        }
    }

    private static void handleClient(Socket clientSocket) {
        try (
                clientSocket;
                BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                OutputStream out = clientSocket.getOutputStream()
        ) {
            String requestLine = in.readLine();
            if (requestLine == null) return;

            if (requestLine.startsWith("CONNECT")) {
                handleConnect(requestLine, in, clientSocket, out);
                return;
            }

            String[] parts = requestLine.split(" ");
            if (parts.length < 3) {
                sendError(out, "400 Bad Request");
                return;
            }

            String method = parts[0];
            String fullUrl = parts[1];
            URL url = new URL(fullUrl);

            // Parse headers
            String headerLine;
            int contentLength = 0;
            StringBuilder headersBuilder = new StringBuilder();
            BufferedReader reader = new BufferedReader(in);
            Map<String, String> headers = new ConcurrentHashMap<>();

            while ((headerLine = reader.readLine()) != null && !headerLine.isEmpty()) {
                int colonIndex = headerLine.indexOf(":");
                if (colonIndex != -1) {
                    String key = headerLine.substring(0, colonIndex).trim();
                    String value = headerLine.substring(colonIndex + 1).trim();
                    headers.put(key, value);

                    if (key.equalsIgnoreCase("Content-Length")) {
                        contentLength = Integer.parseInt(value);
                    }
                }
            }

            // Read body if needed
            StringBuilder bodyBuilder = new StringBuilder();
            if (contentLength > 0) {
                char[] bodyChars = new char[contentLength];
                reader.read(bodyChars, 0, contentLength);
                bodyBuilder.append(bodyChars);
            }

            // Build JSON
            String json = String.format(
                    "{\"method\":\"%s\",\"protocol\":\"%s\",\"hostname\":\"%s\",\"port\":%d,\"path\":\"%s\",\"headers\":%s,\"body\":\"%s\"}",
                    method,
                    url.getProtocol(),
                    url.getHost(),
                    url.getPort() != -1 ? url.getPort() : url.getDefaultPort(),
                    url.getPath().isEmpty() ? "/" : url.getPath(),
                    mapper.writeValueAsString(headers),
                    Base64.getEncoder().encodeToString(bodyBuilder.toString().getBytes())
            );

            CompletableFuture<String> future = new CompletableFuture<>();
            requestQueue.offer(new RequestWrapper(json, future, method));
            String response = future.get();
            parseAndRespond(response, out);


        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void handleConnect(String requestLine, BufferedReader clientIn, Socket clientSocket, OutputStream clientOut) throws IOException {
        String target = requestLine.split(" ")[1];
        String[] parts = target.split(":");
        String hostname = parts[0];
        int port = Integer.parseInt(parts[1]);

        // Skip headers
        String headerLine;
        while ((headerLine = clientIn.readLine()) != null && !headerLine.isEmpty()) {
            // Skip all headers
        }

        String json = String.format("{\"method\":\"CONNECT\",\"hostname\":\"%s\",\"port\":%d}", hostname, port);

        CompletableFuture<String> future = new CompletableFuture<>();
        RequestWrapper wrapper = new RequestWrapper(json, future, "CONNECT");

        synchronized (connectLock) {
            requestQueue.offer(wrapper);

            try {
                String response = future.get(30, TimeUnit.SECONDS);

                if (response == null || !response.contains("200")) {
                    sendError(clientOut, "502 Tunnel Failed");
                    return;
                }

                // Send success response to client
                clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes());
                clientOut.flush();

                // Start tunneling data between client and offshore
                if (wrapper.offshoreSocket != null) {
                    Thread t1 = new Thread(() -> {
                        try {
                            pipeData(clientSocket.getInputStream(), wrapper.offshoreSocket.getOutputStream(), "Client->Offshore");
                        } catch (IOException e) {
                            System.err.println("Error getting client input stream: " + e.getMessage());
                        }
                    });

                    Thread t2 = new Thread(() -> {
                        try {
                            pipeData(wrapper.offshoreSocket.getInputStream(), clientOut, "Offshore->Client");
                        } catch (IOException e) {
                            System.err.println("Error getting offshore input stream: " + e.getMessage());
                        }
                    });

                    t1.start();
                    t2.start();

                    try {
                        t1.join();
                        t2.join();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        try {
                            wrapper.offshoreSocket.close();
                        } catch (IOException e) {
                            System.err.println("Error closing offshore socket: " + e.getMessage());
                        }
                    }
                }
            } catch (TimeoutException e) {
                sendError(clientOut, "504 Gateway Timeout");
            } catch (Exception e) {
                sendError(clientOut, "502 Bad Gateway");
                e.printStackTrace();
            }
        }
    }

    private static void pipeData(InputStream in, OutputStream out, String direction) {
        try {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                out.flush();
            }
        } catch (IOException e) {
            System.err.println("Pipe error (" + direction + "): " + e.getMessage());
        }
    }

    private static void parseAndRespond(String responseJson, OutputStream out) throws IOException {
        int statusCode = extractInt(responseJson, "statusCode");
        String base64Body = extractString(responseJson, "body");
        byte[] bodyBytes = Base64.getDecoder().decode(base64Body);

        String statusLine = "HTTP/1.1 " + statusCode + " OK\r\n";
        String headers = "Content-Length: " + bodyBytes.length + "\r\n\r\n";

        out.write(statusLine.getBytes());
        out.write(headers.getBytes());
        out.write(bodyBytes);
        out.flush();
    }

    private static int extractInt(String json, String key) {
        try {
            JsonNode node = mapper.readTree(json);
            return node.has(key) ? node.get(key).asInt() : 0;
        } catch (Exception e) {
            System.err.println("Failed to parse int: " + e.getMessage());
            return 0;
        }
    }

    private static String extractString(String json, String key) {
        try {
            JsonNode node = mapper.readTree(json);
            return node.has(key) ? node.get(key).asText() : "";
        } catch (Exception e) {
            System.err.println("Failed to parse string: " + e.getMessage());
            return "";
        }
    }

    private static void sendError(OutputStream out, String message) throws IOException {
        String response = "HTTP/1.1 " + message + "\r\nContent-Length: 0\r\n\r\n";
        out.write(response.getBytes());
        out.flush();
    }

    static class RequestWrapper {
        String json;
        CompletableFuture<String> future;
        String method;
        Socket offshoreSocket; // For CONNECT requests

        RequestWrapper(String json, CompletableFuture<String> future, String method) {
            this.json = json;
            this.future = future;
            this.method = method;
        }
    }
}