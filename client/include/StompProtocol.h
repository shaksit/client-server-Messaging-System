#pragma once

#include "../include/ConnectionHandler.h"
#include "../include/event.h"
#include <fstream>
#include <iostream>
#include <map>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>

using namespace std;

class StompProtocol {
private:
  bool isConnected;
  int subscriptionIdCounter;
  int receiptIdCounter;
  int logoutReceiptId;
  std::string currentUsername;

  // Maps topic name to subscription ID
  std::map<std::string, int> topicToSubId;

  // Game updates storage: Game Name -> (User -> List of Events)
  std::map<std::string, std::map<std::string, std::vector<Event>>> gameUpdates;
  std::mutex eventsMutex;

  // Helper to format map for report frames
  std::string mapToString(const std::map<std::string, std::string> &map) {
    string result = "";
    for (auto const &[key, val] : map) {
      result += "\t" + key + ":" + val + "\n";
    }
    return result;
  }

  // Helper to parse MESSAGE frame body
  Event parseEventBody(const std::string &body, std::string &user,
                       std::string &team_a, std::string &team_b) {
    string event_name = "";
    int time = 0;
    map<string, string> general_updates;
    map<string, string> team_a_updates;
    map<string, string> team_b_updates;
    string description = "";

    stringstream ss(body);
    string line;
    string section = "";

    while (getline(ss, line)) {
      if (line.find("user: ") == 0)
        user = line.substr(6);
      else if (line.find("team a: ") == 0)
        team_a = line.substr(8);
      else if (line.find("team b: ") == 0)
        team_b = line.substr(8);
      else if (line.find("event name: ") == 0)
        event_name = line.substr(12);
      else if (line.find("time: ") == 0)
        time = stoi(line.substr(6));
      else if (line == "general game updates:")
        section = "general";
      else if (line == "team a updates:")
        section = "team_a";
      else if (line == "team b updates:")
        section = "team_b";
      else if (line == "description:")
        section = "description";
      else if (section == "description") {
        if (!description.empty())
          description += "\n";
        description += line;
      } else if (!line.empty() && line[0] == '\t') {
        size_t colon = line.find(':');
        if (colon != string::npos) {
          string key = line.substr(1, colon - 1);
          string val = line.substr(colon + 1);
          if (section == "general")
            general_updates[key] = val;
          else if (section == "team_a")
            team_a_updates[key] = val;
          else if (section == "team_b")
            team_b_updates[key] = val;
        }
      }
    }
    return Event(team_a, team_b, event_name, time, general_updates,
                 team_a_updates, team_b_updates, description);
  }

public:
  StompProtocol()
      : isConnected(false), subscriptionIdCounter(0), receiptIdCounter(0),
        logoutReceiptId(-1), currentUsername(""), topicToSubId(), gameUpdates(),
        eventsMutex() {}
  virtual ~StompProtocol() = default;

  // Client Command Handlers
  std::string processLogin(std::string host, short port, std::string username,
                           std::string password) {
    if (isConnected) {
      cout << "The client is already logged in, log out before trying again"
           << endl;
      return "";
    }
    this->currentUsername = username;
    return "CONNECT\naccept-version:1.2\nhost:stomp.cs.bgu.ac.il\nlogin:" +
           username + "\npasscode:" + password + "\n\n\0";
  }

  std::string processJoin(std::string topic) {
    if (!isConnected) {
      cout << "Not logged in" << endl;
      return "";
    }

    int id = subscriptionIdCounter++;
    topicToSubId[topic] = id;
    int receipt = receiptIdCounter++;
    return "SUBSCRIBE\ndestination:/" + topic + "\nid:" + to_string(id) +
           "\nreceipt:" + to_string(receipt) + "\n\n\0";
  }

  std::string processExit(std::string topic) {
    if (!isConnected) {
      cout << "Not logged in" << endl;
      return "";
    }
    if (topicToSubId.find(topic) == topicToSubId.end()) {
      cerr << "Error: Not subscribed to topic " << topic << endl;
      return "";
    }
    int id = topicToSubId[topic];
    topicToSubId.erase(topic);
    int receipt = receiptIdCounter++;
    return "UNSUBSCRIBE\nid:" + to_string(id) +
           "\nreceipt:" + to_string(receipt) + "\n\n\0";
  }

  std::string processLogout() {
    if (!isConnected) {
      cout << "Not logged in" << endl;
      return "";
    }
    int receipt = receiptIdCounter++;
    logoutReceiptId = receipt;
    return "DISCONNECT\nreceipt:" + to_string(receipt) + "\n\n\0";
  }

  std::vector<std::string> processReport(std::string file_path) {
    vector<string> frames;
    if (!isConnected) {
      cout << "Not logged in" << endl;
      return frames;
    }

    names_and_events ne;
    try {
      ne = parseEventsFile(file_path);
    } catch (const exception &e) {
      cerr << "Error parsing file: " << e.what() << endl;
      return frames;
    }

    for (const auto &event : ne.events) {
      string body =
          "user: " + currentUsername + "\n" + "team a: " + ne.team_a_name +
          "\n" + "team b: " + ne.team_b_name + "\n" +
          "event name: " + event.get_name() + "\n" +
          "time: " + to_string(event.get_time()) + "\n" +
          "general game updates:\n" + mapToString(event.get_game_updates()) +
          "team a updates:\n" + mapToString(event.get_team_a_updates()) +
          "team b updates:\n" + mapToString(event.get_team_b_updates()) +
          "description:\n" + event.get_discription();

      string frame = "SEND\ndestination:/" + ne.team_a_name + "_" +
                     ne.team_b_name + "\n" + "file:" + file_path + "\n\n" +
                     body + "\n\0";
      frames.push_back(frame);
    }
    return frames;
  }

  bool processServerFrame(std::string frame) {
    // Basic Parsing
    if (frame.find("CONNECTED") == 0) {
      isConnected = true;
      cout << "Login successful" << endl;
      cout << frame << endl;
    } else if (frame.find("ERROR") == 0) {
      cout << frame << endl;
      if (frame.find("User already logged in") != string::npos) {
        isConnected = false;
      }
    } else if (frame.find("RECEIPT") == 0) {
      size_t idPos = frame.find("receipt-id:");
      if (idPos != string::npos) {
        size_t endLine = frame.find("\n", idPos);
        string recIdStr = frame.substr(idPos + 11, endLine - (idPos + 11));
        try {
          int recId = stoi(recIdStr);
          if (logoutReceiptId != -1 && recId == logoutReceiptId) {
            cout << "Logout confirmed. Exiting." << endl;
            isConnected = false;
            return false; // Signal to terminate
          }
        } catch (...) {
        }
      }
      cout << frame << endl;
    } else if (frame.find("MESSAGE") == 0) {
      size_t bodyPos = frame.find("\n\n");
      if (bodyPos != string::npos) {
        string body = frame.substr(bodyPos + 2);
        string user, team_a, team_b;
        Event event = parseEventBody(body, user, team_a, team_b);

        if (!user.empty() && !team_a.empty() && !team_b.empty()) {
          string game_name = team_a + "_" + team_b;
          lock_guard<mutex> lock(eventsMutex);
          gameUpdates[game_name][user].push_back(event);
        }
      }
      cout << frame << endl;
    } else {
      cout << frame << endl;
    }
    return true;
  }

  void saveGameSummary(std::string game_name, std::string user,
                       std::string file_path) {
    lock_guard<mutex> lock(eventsMutex);
    if (gameUpdates.find(game_name) == gameUpdates.end() ||
        gameUpdates[game_name].find(user) == gameUpdates[game_name].end()) {
      cerr << "No events found for " << user << " in game " << game_name
           << endl;
      return;
    }

    const vector<Event> &events = gameUpdates[game_name][user];
    if (events.empty())
      return;

    string team_a_name = events[0].get_team_a_name();
    string team_b_name = events[0].get_team_b_name();

    // Aggregate Stats
    map<string, string> general_stats;
    map<string, string> team_a_stats;
    map<string, string> team_b_stats;

    for (const auto &event : events) {
      for (auto const &[key, val] : event.get_game_updates())
        general_stats[key] = val;
      for (auto const &[key, val] : event.get_team_a_updates())
        team_a_stats[key] = val;
      for (auto const &[key, val] : event.get_team_b_updates())
        team_b_stats[key] = val;
    }

    ofstream outfile(file_path);
    if (outfile.is_open()) {
      outfile << team_a_name << " vs " << team_b_name << "\n";
      outfile << "Game stats:\n";
      outfile << "General stats:\n";
      for (auto const &[k, v] : general_stats)
        outfile << k << ": " << v << "\n";
      outfile << team_a_name << " stats:\n";
      for (auto const &[k, v] : team_a_stats)
        outfile << k << ": " << v << "\n";
      outfile << team_b_name << " stats:\n";
      for (auto const &[k, v] : team_b_stats)
        outfile << k << ": " << v << "\n";
      outfile << "Game event reports:\n";
      for (const auto &event : events) {
        outfile << event.get_time() << " - " << event.get_name() << ":\n\n";
        outfile << event.get_discription() << "\n\n\n";
      }
      outfile.close();
      cout << "Summary created in " << file_path << endl;
    } else {
      cerr << "Could not open file " << file_path << endl;
    }
  }

  bool isClientConnected() const { return isConnected; }
};
