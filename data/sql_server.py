#!/usr/bin/env python3
"""
Basic Python Server for STOMP Assignment – Stage 3.3

IMPORTANT:
DO NOT CHANGE the server name or the basic protocol.
Students should EXTEND this server by implementing
the methods below.
"""

import socket
import sys
import threading
import sqlite3
import atexit



SERVER_NAME = "STOMP_PYTHON_SQL_SERVER"  # DO NOT CHANGE!
DB_FILE = "stomp_server.db"              # DO NOT CHANGE!

# Global connection object
# connection: sqlite3.Connection = None


def close_db_connection():
    global connection
    if connection:
        try:
            connection.commit()
            connection.close()
        except Exception:
            pass


def recv_null_terminated(sock: socket.socket) -> str:
    data = b""
    while True:
        try:
            chunk = sock.recv(1024)
            if not chunk:
                return ""
            data += chunk
            if b"\0" in data:
                msg, _ = data.split(b"\0", 1)
                return msg.decode("utf-8", errors="replace")
        except Exception:
            return ""


# Create tables: `Users` (username, password), `Logins` (user, time), `Reports` (user, file).
def init_database():
    global connection
    try:
        connection = sqlite3.connect(DB_FILE, check_same_thread=False)
        cursor = connection.cursor()
        cursor.execute('''
            CREATE TABLE IF NOT EXISTS Users (
                username TEXT PRIMARY KEY,
                password TEXT NOT NULL,
                registration_date TIMESTAMP
            )
        ''')
        cursor.execute('''
            CREATE TABLE IF NOT EXISTS login_history (
                username TEXT,
                login_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                logout_time TIMESTAMP,
                FOREIGN KEY(username) REFERENCES Users(username)
            )
        ''')
        cursor.execute('''
            CREATE TABLE IF NOT EXISTS file_tracking (
                username TEXT,
                filename TEXT,
                upload_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                game_channel TEXT,
                FOREIGN KEY(username) REFERENCES Users(username)
            )
        ''')
        connection.commit()
        return connection
    except Exception as e:
        print(f"[{SERVER_NAME}] Database Init Error: {e}")
        sys.exit(1)


def execute_sql_command(sql_command: str) -> str:
    try:
        connection.execute(sql_command)
        connection.commit()
        return "done"
    except Exception as e:
        return f"ERROR: {e}"


def execute_sql_query(sql_query: str) -> str:
    try:
        cursor = connection.cursor()
        cursor.execute(sql_query)
        rows = cursor.fetchall()
        if not rows:
            return "SUCCESS"
        return "SUCCESS|" + "|".join([str(row) for row in rows])
    except Exception as e:
        return f"ERROR: {e}"


def handle_client(client_socket: socket.socket, addr):
    print(f"[{SERVER_NAME}] Client connected from {addr}")

    try:
        while True:
            message = recv_null_terminated(client_socket)
            if message == "":
                break

            print(f"[{SERVER_NAME}] Received:")
            print(message)

            if message.lower().startswith("select"):
                response = execute_sql_query(message)
            else:
                response = execute_sql_command(message)

            client_socket.sendall(response.encode('utf-8') + b"\0")

    except Exception as e:
        print(f"[{SERVER_NAME}] Error handling client {addr}: {e}")
    finally:
        try:
            client_socket.close()
        except Exception:
            pass
        print(f"[{SERVER_NAME}] Client {addr} disconnected")


def start_server(host="127.0.0.1", port=7778):
    global connection
    if connection is None:
        init_database()

    server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)

    try:
        server_socket.bind((host, port))
        server_socket.listen(5)
        print(f"[{SERVER_NAME}] Server started on {host}:{port}")
        print(f"[{SERVER_NAME}] Waiting for connections...")

        while True:
            client_socket, addr = server_socket.accept()
            t = threading.Thread(
                target=handle_client,
                args=(client_socket, addr),
                daemon=True
            )
            t.start()

    except KeyboardInterrupt:
        print(f"\n[{SERVER_NAME}] Shutting down server...")
    finally:
        try:
            server_socket.close()
        except Exception:
            pass


if __name__ == "__main__":
    port = 7778
    if len(sys.argv) > 1:
        raw_port = sys.argv[1].strip()
        try:
            port = int(raw_port)
        except ValueError:
            print(f"Invalid port '{raw_port}', falling back to default {port}")

    init_database()
    atexit.register(close_db_connection)
    start_server(port=port)
else:
    atexit.register(close_db_connection)