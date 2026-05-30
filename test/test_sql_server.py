#!/usr/bin/env python3
"""
Test script for SQL server implementation
"""

import socket
import time
import threading
import os
import sys

# Add data directory to path to find sql_server.py
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '../data'))

def send_sql(sql: str, port=7778) -> str:
    """Send SQL command to server and get response"""
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.connect(("127.0.0.1", port))
        
        # Send SQL with null terminator
        sock.sendall(sql.encode('utf-8') + b'\0')
        
        # Receive response
        data = b""
        while True:
            chunk = sock.recv(1024)
            if not chunk:
                break
            data += chunk
            if b"\0" in data:
                msg, _ = data.split(b"\0", 1)
                sock.close()
                return msg.decode('utf-8')
        
        sock.close()
        return ""
    except Exception as e:
        return f"Connection Error: {e}"

def run_tests():
    """Run test suite"""
    print("=" * 80)
    print("SQL Server Test Suite")
    print("=" * 80)
    
    # Wait a bit for server to start
    time.sleep(1)
    
    # Test 1: Insert user
    print("\n[TEST 1] Inserting user...")
    response = send_sql("INSERT INTO users (username, password, registration_date) VALUES ('alice', 'pass123', datetime('now'))")
    print(f"Response: {response}")
    assert response == "done", f"Expected 'done', got '{response}'"
    print("✓ PASS")
    
    # Test 2: Insert another user
    print("\n[TEST 2] Inserting another user...")
    response = send_sql("INSERT INTO users (username, password, registration_date) VALUES ('bob', 'pass456', datetime('now'))")
    print(f"Response: {response}")
    assert response == "done", f"Expected 'done', got '{response}'"
    print("✓ PASS")
    
    # Test 3: Query users
    print("\n[TEST 3] Querying all users...")
    response = send_sql("SELECT username, password FROM users ORDER BY username")
    print(f"Response: {response}")
    assert response.startswith("SUCCESS"), f"Expected SUCCESS prefix, got '{response}'"
    assert "alice" in response and "bob" in response, "Missing user data"
    print("✓ PASS")
    
    # Test 4: Insert login history
    print("\n[TEST 4] Recording login...")
    response = send_sql("INSERT INTO login_history (username, login_time) VALUES ('alice', datetime('now'))")
    print(f"Response: {response}")
    assert response == "done", f"Expected 'done', got '{response}'"
    print("✓ PASS")
    
    # Test 5: Query login history
    print("\n[TEST 5] Querying login history...")
    response = send_sql("SELECT username, login_time FROM login_history WHERE username='alice'")
    print(f"Response: {response}")
    assert response.startswith("SUCCESS"), f"Expected SUCCESS prefix, got '{response}'"
    assert "alice" in response, "Missing login data"
    print("✓ PASS")
    
    # Test 6: Update logout time
    print("\n[TEST 6] Recording logout...")
    response = send_sql("UPDATE login_history SET logout_time=datetime('now') WHERE username='alice' AND logout_time IS NULL")
    print(f"Response: {response}")
    assert response == "done", f"Expected 'done', got '{response}'"
    print("✓ PASS")
    
    # Test 7: Track file upload
    print("\n[TEST 7] Tracking file upload...")
    response = send_sql("INSERT INTO file_tracking (username, filename, upload_time, game_channel) VALUES ('alice', 'events.json', datetime('now'), 'game1')")
    print(f"Response: {response}")
    assert response == "done", f"Expected 'done', got '{response}'"
    print("✓ PASS")
    
    # Test 8: Query file tracking
    print("\n[TEST 8] Querying file uploads...")
    response = send_sql("SELECT username, filename, game_channel FROM file_tracking WHERE username='alice'")
    print(f"Response: {response}")
    assert response.startswith("SUCCESS"), f"Expected SUCCESS prefix, got '{response}'"
    assert "events.json" in response and "game1" in response, "Missing file data"
    print("✓ PASS")
    
    print("\n" + "=" * 80)
    print("ALL TESTS PASSED! ✓")
    print("=" * 80)

if __name__ == "__main__":
    # In integration tests (via run_all_tests.bat), the server is already running.
    # We should NOT try to start it or delete the DB.
    
    try:
        # Try to connect to see if server is up
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            sock.connect(("127.0.0.1", 7778))
            sock.close()
            print("Detected running SQL server on port 7778.")
        except ConnectionRefusedError:
            print("No external server detected. Starting embedded server...")
            # Start server in background thread ONLY if not running
            from sql_server import start_server
            import sql_server
            # Workaround: Inject connection variable if missing
            if not hasattr(sql_server, "connection"):
                sql_server.connection = None
            
            # Clean up old database only if WE are starting the server
            if os.path.exists("stomp_server.db"):
                try:
                    os.remove("stomp_server.db")
                    print("Cleaned up old database\n")
                except PermissionError:
                    print("Warning: Could not delete stomp_server.db (in use?)")

            server_thread = threading.Thread(target=lambda: start_server(port=7778), daemon=True)
            server_thread.start()
            time.sleep(1) # Wait for startup

        run_tests()
    except AssertionError as e:
        print(f"\n✗ TEST FAILED: {e}")
        sys.exit(1)
    except KeyboardInterrupt:
        print("\n\nTests interrupted by user")
        sys.exit(1)
