#!/usr/bin/env python3
import os
import subprocess
import time
import sys
import signal
import atexit
import select

"""
Linux Test Runner for STOMP Server Assignment
Run this script from the 'test' directory or the project root.
"""

# Determine project root relative to this script
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.abspath(os.path.join(SCRIPT_DIR, ".."))
SERVER_DIR = os.path.join(PROJECT_ROOT, "server")
CLIENT_DIR = os.path.join(PROJECT_ROOT, "client")
DATA_DIR = os.path.join(PROJECT_ROOT, "data")
TEST_DIR = os.path.join(PROJECT_ROOT, "test")

# Configuration
SERVER_PORT = 7777
SQL_PORT = 7778
DB_FILE = "stomp_server.db"

processes = []

# Colors
class Colors:
    HEADER = '\033[95m'
    OKBLUE = '\033[94m'
    OKCYAN = '\033[96m'
    OKGREEN = '\033[92m'
    WARNING = '\033[93m'
    FAIL = '\033[91m'
    ENDC = '\033[0m'
    BOLD = '\033[1m'
    UNDERLINE = '\033[4m'

def print_header(msg):
    print(f"\n{Colors.HEADER}{'='*60}\n{msg}\n{'='*60}{Colors.ENDC}")

def print_step(msg):
    print(f"\n{Colors.OKCYAN}{msg}{Colors.ENDC}")

def print_success(msg):
    print(f"{Colors.OKGREEN}✓ {msg}{Colors.ENDC}")

def print_fail(msg):
    print(f"{Colors.FAIL}✗ {msg}{Colors.ENDC}")

def run_cmd(cmd, cwd=PROJECT_ROOT, check=True, bg=False):
    """Run a shell command."""
    print(f"{Colors.OKBLUE}[{cwd}] $ {cmd}{Colors.ENDC}")
    if bg:
        # setsid ensures we can kill the whole process group later
        p = subprocess.Popen(cmd, shell=True, cwd=cwd, preexec_fn=os.setsid)
        processes.append(p)
        return p
    else:
        ret = subprocess.run(cmd, shell=True, cwd=cwd)
        if check and ret.returncode != 0:
            print_fail(f"Command failed: {cmd}")
            sys.exit(1)
        return ret.returncode

def cleanup():
    """Kill all started background processes."""
    print(f"\n{Colors.WARNING}[Cleanup] Terminating processes...{Colors.ENDC}")
    for p in processes:
        try:
            os.killpg(os.getpgid(p.pid), signal.SIGTERM)
        except:
            pass
    
    # Also try to clean up specific ports/files if possible (optional)
    if os.path.exists(DB_FILE):
        try:
            os.remove(DB_FILE)
        except:
            pass

atexit.register(cleanup)

def main():
    print_header("STOMP Server Assignment - Linux Test Suite")

    # 1. Cleanup Environment
    print_step("[0/5] Cleaning environment...")
    run_cmd(f"rm -f *.log {DB_FILE} server/*.log", check=False)
    
    # 2. Compilation
    print_step("[1/5] Compiling...")
    
    # Server
    print("  [Server] Compiling with Maven...")
    run_cmd("mvn clean compile -q", cwd=SERVER_DIR)
    
    # Tests
    print("  [Tests] Compiling Java Tests...")
    # Classpath with : separator for Linux
    cp = f".:{os.path.join(SERVER_DIR, 'target/classes')}:{TEST_DIR}"
    run_cmd(f"javac -cp '{cp}' *.java", cwd=TEST_DIR)
    
    # Client
    print("  [Client] Check & Compile C++ Client...")
    if os.path.exists(os.path.join(CLIENT_DIR, "makefile")):
        try:
            run_cmd("make clean && make StompWCIClient", cwd=CLIENT_DIR, check=False)
            print_success("Client compiled")
        except: 
            print(f"{Colors.WARNING}  [WARN] Make failed, skipping C++ Client.{Colors.ENDC}")
    else:
        print(f"{Colors.WARNING}  [WARN] No makefile found in client dir. Skipping.{Colors.ENDC}")

    # 3. Start Servers
    print_step("[2/5] Starting Servers...")
    
    # SQL Server
    print("  [SQL] Starting Python SQL Server...")
    run_cmd(f"python3 '{os.path.join(DATA_DIR, 'sql_server.py')}' > sql_server.log 2>&1", bg=True)
    time.sleep(2)
    
    # Java Server
    print(f"\n{Colors.BOLD}Select Server Type:{Colors.ENDC}")
    print("1. TPC")
    print("2. Reactor")
    try:
        # Wait for input with timeout if running non-interactively
        # But for user interactive script, just input()
        choice = input(f"{Colors.BOLD}Enter choice (1/2) [Default: Reactor]: {Colors.ENDC}")
    except (EOFError, KeyboardInterrupt):
        choice = "2"
        
    server_type = "tpc" if choice.strip() == "1" else "reactor"
    
    print(f"  [Java] Starting {server_type} Server...")
    mvn_cmd = (f"mvn exec:java -Dexec.mainClass=bgu.spl.net.impl.stomp.StompServer "
               f"-Dexec.args='{SERVER_PORT} {server_type}'")
    run_cmd(f"{mvn_cmd} > java_server.log 2>&1", cwd=SERVER_DIR, bg=True)
    
    print("  Waiting 5 seconds for startup...")
    time.sleep(5)

    # 4. Run Tests
    print_step("[3/5] Running Tests...")
    
    # SQL Tests
    print("  [Test] Python SQL Logic...")
    run_cmd(f"python3 test_sql_server.py", cwd=TEST_DIR)

    # Client Summary Test
    print("  [Test] C++ Client Summary Generation...")
    run_cmd(f"python3 test_summary.py", cwd=TEST_DIR)
    
    # Java Stress Test
    print("  [Test] StompStressTest...")
    # IMPORTANT: We run from PROJECT_ROOT so paths like test/jsons/... work
    # We need to set classpath relative to PROJECT_ROOT
    cp = f".:{TEST_DIR}" 
    # Must use full class name or ensure directory structure matches package
    # Here StompStressTest is in default package, but source file is in test/
    # If we run from PROJECT_ROOT, we tell java -cp test StompStressTest ??
    # But StompStressTest.java likely expects to be compiled in test dir.
    # The error "java.nio.file.NoSuchFileException: test/jsons/heavy_game.json" means
    # it couldn't find the file relative to CWD.
    # If we run from TEST_DIR, path is "test/jsons..." which is WRONG (it would look for test/test/jsons).
    # If we run from PROJECT_ROOT, path "test/jsons..." is CORRECT.
    # Let's run from PROJECT_ROOT.
    
    # We need to include the compiled test classes in CP.
    # Test classes are in test/*.class (because we ran javac in TEST_DIR)
    cp = f"{TEST_DIR}:{os.path.join(SERVER_DIR, 'target/classes')}"
    # But files are regular Test files in default package.
    # running `java -cp test StompStressTest` from PROJECT_ROOT works if StompStressTest.class is in test/
    
    run_cmd(f"java -cp '{cp}' StompStressTest", cwd=PROJECT_ROOT)

    # 5. Done
    print_header("TEST RUN COMPLETE")
    print(f"Logs: {Colors.BOLD}java_server.log{Colors.ENDC}, {Colors.BOLD}sql_server.log{Colors.ENDC}")
    
    # Cleanup handled by atexit

if __name__ == "__main__":
    main()
