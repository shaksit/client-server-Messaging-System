#include "../include/ConnectionHandler.h"
#include "../include/StompProtocol.h"
#include <iostream>
#include <sstream>
#include <thread>
#include <vector>


using namespace std;

int main(int argc, char *argv[]) {
  if (argc < 3) {
    cerr << "Usage: " << argv[0] << " host port" << endl << endl;
    return -1;
  }
  string host = argv[1];
  short port = atoi(argv[2]);

  ConnectionHandler connectionHandler(host, port);
  if (!connectionHandler.connect()) {
    cerr << "Could not connect to server" << endl;
    return 1;
  }

  StompProtocol protocol;

  // Thread for receiving data
  thread socketThread([&connectionHandler, &protocol]() {
    while (true) {
      string answer;
      if (!connectionHandler.getFrameAscii(answer, '\0')) {
        cout << "Disconnected from server." << endl;
        break;
      }

      if (!protocol.processServerFrame(answer)) {
        // Protocol logic determined we should exit (e.g. logout completed)
        connectionHandler.close();
        break;
      }
    }
  });

  while (true) {
    const short bufsize = 1024;
    char buf[bufsize];
    cin.getline(buf, bufsize);
    string line(buf);
    vector<string> arguments;
    string s;
    stringstream ss(line);
    while (getline(ss, s, ' ')) {
      arguments.push_back(s);
    }

    if (arguments.empty())
      continue;

    try {
      if (arguments[0] == "login") {
        if (arguments.size() < 4) {
          cerr << "Usage: login {host:port} {username} {password}" << endl;
          continue;
        }
        string frame =
            protocol.processLogin(host, port, arguments[2], arguments[3]);
        if (!frame.empty())
          connectionHandler.sendFrameAscii(frame, '\0');

      } else if (arguments[0] == "join") {
        if (arguments.size() < 2) {
          cerr << "Usage: join {topic}" << endl;
          continue;
        }
        string frame = protocol.processJoin(arguments[1]);
        if (!frame.empty())
          connectionHandler.sendFrameAscii(frame, '\0');

      } else if (arguments[0] == "exit") {
        if (arguments.size() < 2) {
          cerr << "Usage: exit {topic}" << endl;
          continue;
        }
        string frame = protocol.processExit(arguments[1]);
        if (!frame.empty())
          connectionHandler.sendFrameAscii(frame, '\0');

      } else if (arguments[0] == "logout") {
        string frame = protocol.processLogout();
        if (!frame.empty())
          connectionHandler.sendFrameAscii(frame, '\0');
        // We wait for the socket thread to close the connection upon receipt

      } else if (arguments[0] == "report") {
        if (arguments.size() < 2) {
          cerr << "Usage: report {file_path}" << endl;
          continue;
        }
        vector<string> frames = protocol.processReport(arguments[1]);
        for (const string &frame : frames) {
          connectionHandler.sendFrameAscii(frame, '\0');
        }

      } else if (arguments[0] == "summary") {
        if (arguments.size() < 4) {
          cerr << "Usage: summary {game_name} {user} {file}" << endl;
          continue;
        }
        protocol.saveGameSummary(arguments[1], arguments[2], arguments[3]);

      } else if (arguments[0] == "stop") {
        break;
      } else {
        cerr << "Unknown command: " << arguments[0] << endl;
      }
    } catch (const exception &e) {
      cerr << "Error: " << e.what() << endl;
    }
  }

  // Wait for socket thread to finish
  if (socketThread.joinable()) {
    socketThread.join();
  }
  return 0;
}
