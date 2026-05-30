import subprocess
import time
import os
import sys

# Configuration
HOST = "127.0.0.1"
PORT = 7777
USER = "summary_tester"
PASS = "pass"
GAME = "Germany_Japan"  # Check events1.json for actual game name match if needed
INPUT_FILE = "input/events1.json"
OUTPUT_FILE = "output/summary_test.txt"
CLIENT_EXE = "../client/bin/StompWCIClient"

def run_test():
    print(f"[SummaryTest] Starting C++ Client Test...")
    
    # Ensure output directory exists
    os.makedirs("output", exist_ok=True)
    
    # Check if client executable exists
    if not os.path.exists(CLIENT_EXE):
        print(f"[SummaryTest] Error: Client executable not found at {CLIENT_EXE}")
        return False

    # Prepare input commands
    commands = f"""login {HOST}:{PORT} {USER} {PASS}
join {GAME}
report {INPUT_FILE}
summary {GAME} {USER} {OUTPUT_FILE}
logout
stop
"""
    
    try:
        # Run Client
        process = subprocess.Popen(
            [CLIENT_EXE, HOST, str(PORT)],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True
        )
        
        print(f"[SummaryTest] Sending login...")
        process.stdin.write(f"login {HOST}:{PORT} {USER} {PASS}\n")
        process.stdin.flush()
        time.sleep(1) # Wait for CONNECTED

        print(f"[SummaryTest] Joining game...")
        process.stdin.write(f"join {GAME}\n")
        process.stdin.flush()
        time.sleep(0.5)

        print(f"[SummaryTest] Reporting events...")
        process.stdin.write(f"report {INPUT_FILE}\n")
        process.stdin.flush()
        time.sleep(2) # Wait for file processing and echo verification

        print(f"[SummaryTest] Generating summary...")
        process.stdin.write(f"summary {GAME} {USER} {OUTPUT_FILE}\n")
        process.stdin.write("logout\n")
        process.stdin.write("stop\n")
        process.stdin.flush()
        
        # Wait for summary file write
        time.sleep(2)
        
        # Force kill
        process.kill()
        
        # Read whatever output was produced
        stdout, stderr = process.communicate()
        print(f"[SummaryTest] Client Output (Partial):\n{stdout}")
        
        if stderr:
             print(f"[SummaryTest] Client Errors:\n{stderr}")

        # Verify Output File
        if os.path.exists(OUTPUT_FILE):
            print(f"[SummaryTest] SUCCESS: Summary file created at {OUTPUT_FILE}")
            
            # Optional: Read content to verify stats
            with open(OUTPUT_FILE, 'r') as f:
                content = f.read()
                print(f"[SummaryTest] content preview:\n{content[:200]}...")
                
            return True
        else:
            print(f"[SummaryTest] FAILED: Summary file was not created.")
            return False
            
    except Exception as e:
        print(f"[SummaryTest] FAILED: {e}")
        return False

if __name__ == "__main__":
    if run_test():
        sys.exit(0)
    else:
        sys.exit(1)
