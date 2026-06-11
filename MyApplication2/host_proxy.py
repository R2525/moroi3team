import socket
import threading

LISTEN_HOST = '127.0.0.1'
LISTEN_PORT = 8000
TARGET_HOST = '172.17.68.60'
TARGET_PORT = 8000


def forward(src, dst):
    try:
        while True:
            data = src.recv(4096)
            if not data:
                break
            dst.sendall(data)
    except OSError:
        pass
    finally:
        try:
            src.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        try:
            dst.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass


def handle_client(client_socket):
    try:
        remote_socket = socket.create_connection((TARGET_HOST, TARGET_PORT), timeout=10)
    except OSError as exc:
        print(f'Failed to connect to {TARGET_HOST}:{TARGET_PORT}: {exc}')
        client_socket.close()
        return

    client_thread = threading.Thread(target=forward, args=(client_socket, remote_socket), daemon=True)
    remote_thread = threading.Thread(target=forward, args=(remote_socket, client_socket), daemon=True)
    client_thread.start()
    remote_thread.start()
    client_thread.join()
    remote_thread.join()
    client_socket.close()
    remote_socket.close()


def main():
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind((LISTEN_HOST, LISTEN_PORT))
    server.listen(5)
    print(f'Proxy listening on {LISTEN_HOST}:{LISTEN_PORT} forwarding to {TARGET_HOST}:{TARGET_PORT}')
    try:
        while True:
            client_socket, addr = server.accept()
            thread = threading.Thread(target=handle_client, args=(client_socket,), daemon=True)
            thread.start()
    except KeyboardInterrupt:
        pass
    finally:
        server.close()


if __name__ == '__main__':
    main()
