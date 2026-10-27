#!/usr/bin/env python3
"""
Telegram-Invisible-Bridge (tg_bridge.py)
Автономный локальный микро-мост для Telegram с защитой от ТСПУ (DPI).
Не использует системный VPN и не блокирует банковские приложения.
"""

import socket
import select
import threading
import time
import sys

LISTEN_HOST = "127.0.0.1"
LISTEN_PORT = 10808
FRAGMENT_SIZE = 3      # Размер первого пакета для расщепления сигнатуры DPI
FRAGMENT_DELAY = 0.005  # Задержка 5 мс между фрагментами

def handle_client(client_socket):
    remote_socket = None
    try:
        client_socket.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)

        # 1. SOCKS5 Приветствие (Handshake)
        header = client_socket.recv(2)
        if len(header) < 2 or header[0] != 5:
            client_socket.close()
            return
        
        num_methods = header[1]
        client_socket.recv(num_methods)
        # Ответ: версия 5, метод 00 (без аутентификации)
        client_socket.sendall(b"\x05\x00")

        # 2. SOCKS5 Запрос подключения (CONNECT)
        req = client_socket.recv(4)
        if len(req) < 4 or req[1] != 1:  # CMD 1 = CONNECT
            client_socket.close()
            return

        atyp = req[3]
        target_host = None

        if atyp == 1:  # IPv4
            raw_ip = client_socket.recv(4)
            target_host = socket.inet_ntoa(raw_ip)
        elif atyp == 3:  # Domain
            domain_len = client_socket.recv(1)[0]
            target_host = client_socket.recv(domain_len).decode("latin-1")
        elif atyp == 4:  # IPv6
            raw_ip = client_socket.recv(16)
            target_host = socket.inet_ntop(socket.AF_INET6, raw_ip)
        else:
            client_socket.close()
            return

        port_data = client_socket.recv(2)
        target_port = int.from_bytes(port_data, "big")

        # 3. Подключение к удаленному серверу Telegram
        remote_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        remote_socket.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        remote_socket.settimeout(10.0)

        try:
            remote_socket.connect((target_host, target_port))
        except Exception as e:
            # Ответ: ошибка подключения
            client_socket.sendall(b"\x05\x05\x00\x01\x00\x00\x00\x00\x00\x00")
            client_socket.close()
            return

        # Ответ: подключение успешно установлено
        client_socket.sendall(b"\x05\x00\x00\x01\x00\x00\x00\x00\x00\x00")

        # 4. Двунаправленная передача данных с Anti-DPI расщеплением первого пакета
        client_socket.setblocking(False)
        remote_socket.setblocking(False)

        first_client_packet = True
        sockets = [client_socket, remote_socket]

        while True:
            rlist, _, xlist = select.select(sockets, [], sockets, 60)
            if xlist or not rlist:
                break

            for s in rlist:
                other = remote_socket if s is client_socket else client_socket
                try:
                    data = s.recv(16384)
                except Exception:
                    data = b""

                if not data:
                    return

                if s is client_socket and first_client_packet:
                    first_client_packet = False
                    # Anti-DPI: расщепляем первый пакет рукопожатия
                    if len(data) > FRAGMENT_SIZE:
                        part1 = data[:FRAGMENT_SIZE]
                        part2 = data[FRAGMENT_SIZE:]
                        other.sendall(part1)
                        time.sleep(FRAGMENT_DELAY)
                        other.sendall(part2)
                    else:
                        other.sendall(data)
                else:
                    other.sendall(data)

    except Exception:
        pass
    finally:
        try:
            client_socket.close()
        except Exception:
            pass
        if remote_socket:
            try:
                remote_socket.close()
            except Exception:
                pass

def start_server():
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind((LISTEN_HOST, LISTEN_PORT))
    server.listen(128)

    print("=" * 60)
    print(f"[*] Telegram-Invisible-Bridge запущен на {LISTEN_HOST}:{LISTEN_PORT}")
    print("[*] Режим: SOCKS5 Anti-DPI (без системного VPN)")
    print("[*] Ссылка для Telegram в 1 клик:")
    print(f"    tg://socks?server={LISTEN_HOST}&port={LISTEN_PORT}")
    print("=" * 60)

    try:
        while True:
            client, _ = server.accept()
            t = threading.Thread(target=handle_client, args=(client,), daemon=True)
            t.start()
    except KeyboardInterrupt:
        print("\nОстановка моста...")
    finally:
        server.close()

if __name__ == "__main__":
    start_server()
