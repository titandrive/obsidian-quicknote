// Stands in for better-sqlite3, a native module that would have to be built for Android, with
// Node's own SQLite (node:sqlite), for the calls Obsidian Headless makes: open a database,
// pragma, exec, prepare (run, get, all), close. Its tables hold only text.
"use strict";
const { DatabaseSync } = require("node:sqlite");

class Database {
  constructor(path) {
    this.db = new DatabaseSync(path);
  }

  pragma(source) {
    return this.db.prepare("PRAGMA " + source).all();
  }

  exec(sql) {
    this.db.exec(sql);
    return this;
  }

  prepare(sql) {
    return this.db.prepare(sql);
  }

  transaction(fn) {
    return (...args) => {
      this.db.exec("BEGIN");
      try {
        const result = fn(...args);
        this.db.exec("COMMIT");
        return result;
      } catch (e) {
        this.db.exec("ROLLBACK");
        throw e;
      }
    };
  }

  close() {
    this.db.close();
  }
}

module.exports = Database;
module.exports.default = Database;
