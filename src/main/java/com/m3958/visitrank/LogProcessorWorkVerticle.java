package com.m3958.visitrank;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.bson.types.ObjectId;
import org.vertx.java.core.AsyncResult;
import org.vertx.java.core.Handler;
import org.vertx.java.core.eventbus.Message;
import org.vertx.java.core.json.JsonObject;
import org.vertx.java.platform.Verticle;

import com.m3958.visitrank.logger.AppLogger;
import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBObject;
import com.mongodb.MongoClient;
import com.mongodb.WriteResult;
import com.mongodb.util.JSON;

/**
 * it's a sync worker verticle. First we start a mongodb connection, readlines from logfile,batchly
 * insert into mongodb. according log filename,hourly 2014-03-03-01,construct hourly dbname,daily
 * dbname 2014-03-03.maybe monthly dbname:2014-03,yearly dbname 2014.
 * 这个verticle具有唯一的事件地址（logfilename），使用一次，然后销毁。
 * 
 * @author jianglibo@gmail.com
 * 
 */
public class LogProcessorWorkVerticle extends Verticle {

  public static class LogProcessorWorkCfgKey {
    public static String FILE_NAME = "filename";
    public static String ADDRESS = "address";
    public static String LOG_DIR = "logDir";
    public static String ARCHIVE_DIR = "archiveDir";
    public static String REPLY = "reply";
  }

  @Override
  public void start() {
    JsonObject cfg = container.config();

    final String filename = cfg.getString(LogProcessorWorkCfgKey.FILE_NAME);
    final String address = cfg.getString(LogProcessorWorkCfgKey.ADDRESS, filename);
    final String logDir = cfg.getString(LogProcessorWorkCfgKey.LOG_DIR, "logs");
    final String archiveDir = cfg.getString(LogProcessorWorkCfgKey.ARCHIVE_DIR, "archives");
    final boolean reply = cfg.getBoolean(LogProcessorWorkCfgKey.REPLY, false);

    vertx.eventBus().registerHandler(address, new Handler<Message<String>>() {
      @Override
      public void handle(Message<String> message) {
        final String thisDeployId = message.body();

        new LogProcessor(logDir, archiveDir, filename).process();
        if (reply) {
          message.reply("done");
        }

        container.undeployVerticle(thisDeployId, new Handler<AsyncResult<Void>>() {
          @Override
          public void handle(AsyncResult<Void> ar) {
            if (ar.failed()) {
              AppLogger.deployError.error("undeploy " + thisDeployId + " failure");
            }
          }
        });
      }
    });
  }

  public static class LogProcessor {
    private String filename;
    private String logDir;
    private String archiveDir;

    public LogProcessor(String logDir, String archiveDir, String filename) {
      this.logDir = logDir;
      this.archiveDir = archiveDir;
      this.filename = filename;
    }

    public void process() {
      AppLogger.processLogger.info("process " + filename + " starting.");
      try {
        Path logfilePath = Paths.get(logDir, filename);

        MongoClient mongoClient =
            new MongoClient(AppConstants.MONGODB_HOST, AppConstants.MONGODB_PORT);
        DB db = mongoClient.getDB(AppUtils.getDailyDbName(filename));
        ObjectId hourJobId = insertHourJobStart(db);
        DBCollection coll = db.getCollection(AppConstants.MongoNames.PAGE_VISIT_COL_NAME);
        BufferedReader reader =
            new BufferedReader(new InputStreamReader(new FileInputStream(logfilePath.toFile()),
                "UTF-8"));
        OutputStreamWriter partialWriter =
            new OutputStreamWriter(new FileOutputStream(Paths.get(logDir, filename + ".partial")
                .toFile()), "UTF-8");

        List<DBObject> dbos = new ArrayList<>();
        String line;
        long counter = 0;
        while ((line = reader.readLine()) != null) {
          try {
            dbos.add((DBObject) JSON.parse(line));
          } catch (Exception e) {
            AppLogger.error.error("parse exception:" + line);
          }
          counter++;
          if (counter % 100 == 0) {
            partialWriter.write(counter + ",");
            partialWriter.flush();
            WriteResult wr = coll.insert(dbos);
            partialWriter.write(counter + "\n");
            partialWriter.flush();
            dbos = new ArrayList<>();
          }
        }
        if (dbos.size() > 0) {
          partialWriter.write(counter + ",");
          partialWriter.flush();
          coll.insert(dbos);
          partialWriter.write(counter + "\n");
          partialWriter.flush();
        }
        reader.close();
        partialWriter.close();
        updateHourJobEnd(db, hourJobId);
        AppUtils.releaseLock(logDir, filename);
        mongoClient.close();

        moveLogFiles(logfilePath);
        AppLogger.processLogger.info("process " + filename + " end.");
      } catch (UnsupportedEncodingException | FileNotFoundException e) {
        AppLogger.error.error("cann't create reader from file: " + filename);
      } catch (UnknownHostException e1) {
        AppLogger.error.error("cann't connect to mongo host: " + filename);
      } catch (IOException e) {
        e.printStackTrace();
      }
    }

    private void moveLogFiles(Path logfilePath) throws IOException {
      Path archiedPath = Paths.get(archiveDir);
      if (!archiedPath.toFile().exists()) {
        Files.createDirectories(archiedPath);
      }
      if (Files.exists(archiedPath.resolve(filename), LinkOption.NOFOLLOW_LINKS)) {
        Files.move(logfilePath, archiedPath.resolve(filename + ".duplicated"));
      } else {
        Files.move(logfilePath, archiedPath.resolve(filename));
      }
      if (Files.exists(Paths.get(logDir, filename + ".partial"), LinkOption.NOFOLLOW_LINKS)) {
        Files.delete(Paths.get(logDir, filename + ".partial"));
      }
    }

    private ObjectId insertHourJobStart(DB db) {
      DBCollection hourlyCol = db.getCollection(AppConstants.MongoNames.HOURLY_JOB_COL_NAME);
      DBObject dbo =
          new BasicDBObject().append(AppConstants.MongoNames.HOURLY_JOB_NUMBER_KEY,
              AppUtils.getHour(filename)).append(AppConstants.MongoNames.HOURLY_JOB_STATUS_KEY,
              "start");
      hourlyCol.insert(dbo);
      return (ObjectId) dbo.get("_id");
    }

    private void updateHourJobEnd(DB db, ObjectId id) {
      DBCollection hourlyCol = db.getCollection(AppConstants.MongoNames.HOURLY_JOB_COL_NAME);
      hourlyCol.findAndModify(new BasicDBObject("_id", id), new BasicDBObject(
          AppConstants.MongoNames.HOURLY_JOB_STATUS_KEY, "end"));
    }
  }
}
