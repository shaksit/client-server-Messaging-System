# Assignment 3 - SPL
# Client-Server Messaging System
A robust, high-performance messaging application developed as part of the Systems Programming course.

## Overview
This project demonstrates the implementation of a concurrent client-server architecture using the STOMP protocol. It highlights advanced concepts such as multi-threading, synchronization, and protocol design, integrating a Java-based server with a C++ client.

## Core Challenges Solved
- **Concurrency**: Implemented both Thread-Per-Client (TPC) and Reactor server models to handle multiple concurrent users efficiently.
- **Protocol Implementation**: Built a robust messaging system based on the STOMP protocol.
- **System Integration**: Managed inter-process communication between a Java server and a C++ client with a shared SQL database.
- 
## Names
David Vaiser 
Shaked Sitruk 

## Submission date
21/01/2026

## Project Structure

server/
├── src/                # Java Server Source Code
├── pom.xml             # Maven Configuration
└── target/             # Compiled Java classes

client/
├── src/                # C++ Client Source Code
├── include/            # C++ Header Files
├── bin/                # Compiled C++ Executable
└── makefile            # Build Script

data/
└── sql_server.py       # Python SQL Server

test/
├── run_linux_tests.py     # Automated Linux Test Runner
├── A few StompTests.java  # Java tests and Integration Tests
└── test_summary.py        # Automated Summary Verification Script

## How to Run

### 1. Build the Project

**Server (Java):**
Navigate to the `server` directory and compile with Maven:
```bash
cd server
mvn clean compile
```

**Client (C++):**
Navigate to the `client` directory and compile with Make:
```bash
cd client
make
```

### 2. Run the Components

You need to run the components in the following order:

**Step A: Start SQL Server**
In a terminal, run the Python SQL server:
```bash
python3 data/sql_server.py
```
*Port: 7778*

**Step B: Start Java Server**
In a **new** terminal, start the Java server (Reactor or TPC):
```bash
cd server
mvn exec:java -Dexec.mainClass=bgu.spl.net.impl.stomp.StompServer -Dexec.args="7777 reactor"
```
*Port: 7777*

**Step C: Run Client**
In a **third** terminal, run the client:
```bash
cd client
bin/StompWCIClient 127.0.0.1 7777
```

### 3. Automated Testing (Optional)

We have included a comprehensive test runner for Linux/macOS environments.
This script cleans the project, compiles everything, starts servers in the background, functions checks, and runs stress tests.

To run the full suite:
```bash
python3 test/run_linux_tests.py
```

## Requirements
- Java 8 or higher
- C++17 Compatible Compiler (g++)
- Python 3.x
- Maven
