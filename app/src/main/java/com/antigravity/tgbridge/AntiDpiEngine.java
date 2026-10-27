package com.antigravity.tgbridge;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Высокопроизводительный локальный SOCKS5 сервер с Anti-DPI расщеплением пакетов.
 * Работает автономно в фоне без запроса разрешений Android VpnService.
 */
public class AntiDpiEngine {
    public static final String LISTEN_HOST = "127.0.0.1";
    public static final int LISTEN_PORT = 10808;
    private static final int FRAGMENT_SIZE = 3;
    private static final int FRAGMENT_DELAY_MS = 5;

    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private ServerSocket serverSocket;
    private ExecutorService threadPool;

    public synchronized void start() throws Exception {
        if (isRunning.get()) return;

        serverSocket = new ServerSocket(LISTEN_PORT, 128, InetAddress.getByName(LISTEN_HOST));
        threadPool = Executors.newCachedThreadPool();
        isRunning.set(true);

        threadPool.execute(() -> {
            while (isRunning.get() && !serverSocket.isClosed()) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    threadPool.execute(() -> handleClient(clientSocket));
                } catch (Exception e) {
                    if (!isRunning.get()) break;
                }
            }
        });
    }

    public synchronized void stop() {
        isRunning.set(false);
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (Exception ignored) {}

        if (threadPool != null && !threadPool.isShutdown()) {
            threadPool.shutdownNow();
        }
    }

    public boolean isRunning() {
        return isRunning.get();
    }

    private void handleClient(Socket clientSocket) {
        Socket remoteSocket = null;
        try {
            clientSocket.setTcpNoDelay(true);
            InputStream clientIn = clientSocket.getInputStream();
            OutputStream clientOut = clientSocket.getOutputStream();

            // 1. SOCKS5 Handshake
            int ver = clientIn.read();
            if (ver != 5) {
                clientSocket.close();
                return;
            }

            int nMethods = clientIn.read();
            byte[] methods = new byte[nMethods];
            int read = clientIn.read(methods);
            if (read < nMethods) {
                clientSocket.close();
                return;
            }

            // Ответ: Версия 5, Без аутентификации (0x00)
            clientOut.write(new byte[]{0x05, 0x00});
            clientOut.flush();

            // 2. SOCKS5 Request
            byte[] reqHeader = new byte[4];
            if (clientIn.read(reqHeader) < 4 || reqHeader[1] != 1) { // 1 = CONNECT
                clientSocket.close();
                return;
            }

            int atyp = reqHeader[3] & 0xFF;
            String targetHost;

            if (atyp == 1) { // IPv4
                byte[] ipBytes = new byte[4];
                clientIn.read(ipBytes);
                targetHost = InetAddress.getByAddress(ipBytes).getHostAddress();
            } else if (atyp == 3) { // Domain
                int len = clientIn.read();
                byte[] domainBytes = new byte[len];
                clientIn.read(domainBytes);
                targetHost = new String(domainBytes);
            } else if (atyp == 4) { // IPv6
                byte[] ipBytes = new byte[16];
                clientIn.read(ipBytes);
                targetHost = InetAddress.getByAddress(ipBytes).getHostAddress();
            } else {
                clientSocket.close();
                return;
            }

            byte[] portBytes = new byte[2];
            clientIn.read(portBytes);
            int targetPort = ((portBytes[0] & 0xFF) << 8) | (portBytes[1] & 0xFF);

            // 3. Подключение к удаленному серверу Telegram
            remoteSocket = new Socket(targetHost, targetPort);
            remoteSocket.setTcpNoDelay(true);
            remoteSocket.setSoTimeout(60000);

            // Ответ клиенту: Подключение успешно (0x00)
            clientOut.write(new byte[]{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0});
            clientOut.flush();

            // 4. Двунаправленная пересылка данных
            InputStream remoteIn = remoteSocket.getInputStream();
            OutputStream remoteOut = remoteSocket.getOutputStream();

            Socket finalRemote = remoteSocket;

            // Поток Client -> Remote с Anti-DPI фрагментацией
            Thread forwardUpstream = new Thread(() -> {
                try {
                    byte[] buffer = new byte[16384];
                    boolean isFirstPacket = true;
                    int bytesRead;

                    while ((bytesRead = clientIn.read(buffer)) != -1) {
                        if (isFirstPacket) {
                            isFirstPacket = false;
                            // Anti-DPI: расщепляем первый пакет рукопожатия
                            if (bytesRead > FRAGMENT_SIZE) {
                                remoteOut.write(buffer, 0, FRAGMENT_SIZE);
                                remoteOut.flush();

                                Thread.sleep(FRAGMENT_DELAY_MS);

                                remoteOut.write(buffer, FRAGMENT_SIZE, bytesRead - FRAGMENT_SIZE);
                                remoteOut.flush();
                            } else {
                                remoteOut.write(buffer, 0, bytesRead);
                                remoteOut.flush();
                            }
                        } else {
                            remoteOut.write(buffer, 0, bytesRead);
                            remoteOut.flush();
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    closeQuietly(finalRemote);
                    closeQuietly(clientSocket);
                }
            });

            // Поток Remote -> Client
            Thread forwardDownstream = new Thread(() -> {
                try {
                    byte[] buffer = new byte[16384];
                    int bytesRead;
                    while ((bytesRead = remoteIn.read(buffer)) != -1) {
                        clientOut.write(buffer, 0, bytesRead);
                        clientOut.flush();
                    }
                } catch (Exception ignored) {
                } finally {
                    closeQuietly(finalRemote);
                    closeQuietly(clientSocket);
                }
            });

            forwardUpstream.start();
            forwardDownstream.start();

        } catch (Exception e) {
            closeQuietly(remoteSocket);
            closeQuietly(clientSocket);
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (Exception ignored) {}
        }
    }
}
