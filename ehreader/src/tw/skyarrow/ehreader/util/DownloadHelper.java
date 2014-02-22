package tw.skyarrow.ehreader.util;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;

import java.util.List;

import de.greenrobot.dao.query.QueryBuilder;
import tw.skyarrow.ehreader.Constant;
import tw.skyarrow.ehreader.db.DaoMaster;
import tw.skyarrow.ehreader.db.DaoSession;
import tw.skyarrow.ehreader.db.Download;
import tw.skyarrow.ehreader.db.DownloadDao;
import tw.skyarrow.ehreader.service.GalleryDownloadService;

/**
 * Created by SkyArrow on 2014/2/21.
 */
public class DownloadHelper {
    private static DownloadHelper instance = null;
    private Context context;
    private SQLiteDatabase db;
    private DownloadDao downloadDao;

    public static DownloadHelper getInstance() {
        if (instance == null) {
            instance = new DownloadHelper();
        }

        return instance;
    }

    public void init(Context context) {
        this.context = context;

        setupDatabase();
    }

    private void setupDatabase() {
        DaoMaster.DevOpenHelper helper = new DaoMaster.DevOpenHelper(context, Constant.DB_NAME, null);
        db = helper.getWritableDatabase();
        DaoMaster daoMaster = new DaoMaster(db);
        DaoSession daoSession = daoMaster.newSession();
        downloadDao = daoSession.getDownloadDao();
    }

    public void startAll() {
        QueryBuilder<Download> qb = downloadDao.queryBuilder();
        qb.where(DownloadDao.Properties.Status.notIn(Download.STATUS_SUCCESS, Download.STATUS_ERROR));
        List<Download> list = qb.list();

        for (Download download : list) {
            Intent intent = new Intent(context, GalleryDownloadService.class);

            intent.setAction(GalleryDownloadService.ACTION_START);
            intent.putExtra(GalleryDownloadService.GALLERY_ID, download.getId());
            context.startService(intent);
        }
    }

    public void pauseAll() {
        Intent intent = new Intent(context, GalleryDownloadService.class);

        context.stopService(intent);
    }

    public boolean isServiceRunning() {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        String name = GalleryDownloadService.class.getName();

        for (ActivityManager.RunningServiceInfo info : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (name.equals(info.service.getClassName())) {
                return true;
            }
        }

        return false;
    }
}
