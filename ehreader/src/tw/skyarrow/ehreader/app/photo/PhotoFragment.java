package tw.skyarrow.ehreader.app.photo;

import android.content.ContentUris;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.Fragment;
import android.support.v4.view.MenuItemCompat;
import android.support.v7.widget.ShareActionProvider;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.analytics.tracking.android.MapBuilder;
import com.nostra13.universalimageloader.core.DisplayImageOptions;
import com.nostra13.universalimageloader.core.ImageLoader;
import com.nostra13.universalimageloader.core.assist.FailReason;
import com.nostra13.universalimageloader.core.assist.ImageLoadingProgressListener;
import com.nostra13.universalimageloader.core.assist.ImageScaleType;
import com.nostra13.universalimageloader.core.assist.SimpleImageLoadingListener;

import org.apache.http.HttpResponse;
import org.apache.http.client.CookieStore;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.protocol.ClientContext;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.cookie.BasicClientCookie;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HttpContext;

import java.io.File;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import butterknife.ButterKnife;
import butterknife.InjectView;
import butterknife.OnClick;
import de.greenrobot.event.EventBus;
import tw.skyarrow.ehreader.BaseApplication;
import tw.skyarrow.ehreader.Constant;
import tw.skyarrow.ehreader.R;
import tw.skyarrow.ehreader.api.DataLoader;
import tw.skyarrow.ehreader.app.search.ImageSearchActivity;
import tw.skyarrow.ehreader.db.DaoMaster;
import tw.skyarrow.ehreader.db.DaoSession;
import tw.skyarrow.ehreader.db.Photo;
import tw.skyarrow.ehreader.db.PhotoDao;
import tw.skyarrow.ehreader.event.PhotoDownloadEvent;
import tw.skyarrow.ehreader.event.PhotoInfoEvent;
import tw.skyarrow.ehreader.provider.PhotoProvider;
import tw.skyarrow.ehreader.service.PhotoInfoService;
import tw.skyarrow.ehreader.util.DatabaseHelper;
import tw.skyarrow.ehreader.util.FileInfoHelper;
import tw.skyarrow.ehreader.util.HttpRequestHelper;
import tw.skyarrow.ehreader.util.L;
import uk.co.senab.photoview.PhotoViewAttacher;

/**
 * Created by SkyArrow on 2014/1/31.
 */
public class PhotoFragment extends Fragment {
    @InjectView(R.id.page)
    TextView pageText;

    @InjectView(R.id.progress)
    ProgressBar progressBar;

    @InjectView(R.id.image)
    ImageView imageView;

    @InjectView(R.id.retry)
    Button retryBtn;

    public static final String TAG = "PhotoFragment";

    public static final String EXTRA_GALLERY = "id";
    public static final String EXTRA_PAGE = "page";
    public static final String EXTRA_TITLE = "title";

    private static final int QUALITY_ORIGINAL = 0;
    private static final int QUALITY_LOW = 1;
    private static final int QUALITY_MEDIUM = 2;
    private static final int QUALITY_HIGH = 3;

    private static final Pattern pLofiSrc = Pattern.compile("<img id=\"sm\" src=\"(.+?)\".+?>");

    private SQLiteDatabase db;
    private PhotoDao photoDao;

    private ImageLoader imageLoader;
    private DisplayImageOptions displayOptions;
    private DataLoader dataLoader;

    private long galleryId;
    private int page;
    private String galleryTitle;
    private Photo photo;
    private Bitmap mBitmap;
    private int pictureQuality;

    private boolean isLoaded = false;
    private boolean isServiceCalled = false;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_photo, container, false);
        ButterKnife.inject(this, view);
        setHasOptionsMenu(true);
        EventBus.getDefault().register(this);

        view.setClickable(true);
        view.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {
                return gestureDetector.onTouchEvent(motionEvent);
            }
        });

        DatabaseHelper helper = DatabaseHelper.getInstance(getActivity());
        db = helper.getWritableDatabase();
        DaoMaster daoMaster = new DaoMaster(db);
        DaoSession daoSession = daoMaster.newSession();
        photoDao = daoSession.getPhotoDao();
        dataLoader = DataLoader.getInstance(getActivity());

        imageLoader = ImageLoader.getInstance();
        displayOptions = new DisplayImageOptions.Builder()
                .cacheOnDisc(true)
                .imageScaleType(ImageScaleType.EXACTLY)
                .bitmapConfig(Bitmap.Config.RGB_565)
                .imageScaleType(ImageScaleType.IN_SAMPLE_INT)
                .build();

        Bundle args = getArguments();
        galleryId = args.getLong(EXTRA_GALLERY);
        page = args.getInt(EXTRA_PAGE);
        galleryTitle = args.getString(EXTRA_TITLE);

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getActivity());
        String qualityPref = preferences.getString(getString(R.string.pref_picture_quality), getString(R.string.pref_picture_quality_default));
        pictureQuality = Integer.parseInt(qualityPref);

        pageText.setText(Integer.toString(page));
        displayPhoto();

        return view;
    }

    private GestureDetector gestureDetector = new GestureDetector(getActivity(),
            new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onSingleTapUp(MotionEvent e) {
                    ((PhotoActivity) getActivity()).toggleUIVisibility();
                    return true;
                }
            });

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        imageLoader.cancelDisplayTask(imageView);
        EventBus.getDefault().unregister(this);

        if (mBitmap != null && !mBitmap.isRecycled()) {
            mBitmap.recycle();
        }
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        if (!isLoaded) return;

        inflater.inflate(R.menu.photo_fragment, menu);

        boolean isBookmarked = photo.getBookmarked();

        menu.findItem(R.id.menu_add_bookmark).setVisible(!isBookmarked);
        menu.findItem(R.id.menu_remove_bookmark).setVisible(isBookmarked);

        if (mBitmap != null) {
            inflater.inflate(R.menu.photo_fragment_loaded, menu);

            MenuItem shareItem = menu.findItem(R.id.menu_share);
            ShareActionProvider shareActionProvider = (ShareActionProvider) MenuItemCompat.getActionProvider(shareItem);
            shareActionProvider.setShareIntent(getShareIntent());

            boolean isDownloaded = photo.getDownloaded();

            menu.findItem(R.id.menu_save_photo).setVisible(!isDownloaded);
            menu.findItem(R.id.menu_delete_photo).setVisible(isDownloaded);
        }

        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_add_bookmark:
                setBookmark(true);
                return true;

            case R.id.menu_remove_bookmark:
                setBookmark(false);
                return true;

            case R.id.menu_set_as_wallpaper:
                setAsWallpaper();
                return true;

            case R.id.menu_find_similar:
                findSimilar();
                return true;

            case R.id.menu_open_in_browser:
                openInBrowser();
                return true;

            case R.id.menu_save_photo:
                savePhoto();
                return true;

            case R.id.menu_delete_photo:
                deletePhoto();
                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    public void onEventMainThread(PhotoInfoEvent event) {
        if (event.getGalleryId() != galleryId || event.getPage() != page) return;

        photo = event.getPhoto();

        if (photo == null) {
            showRetryBtn();
            return;
        }

        getActivity().supportInvalidateOptionsMenu();
        loadImage();
    }

    public void onEvent(PhotoDownloadEvent event) {
        if (event.getId() != photo.getId()) return;

        photo.setDownloaded(event.isDownloaded());
        getActivity().supportInvalidateOptionsMenu();
    }

    private Intent getShareIntent() {
        Intent intent = new Intent(Intent.ACTION_SEND);
        String filename = photo.getFilename();

        intent.setType(FileInfoHelper.getMimeType(filename));
        intent.putExtra(Intent.EXTRA_TEXT, galleryTitle + " " + photo.getUrl());
        intent.putExtra(Intent.EXTRA_STREAM, getPhotoUri());

        return intent;
    }

    private Uri getPhotoUri() {
        return ContentUris.withAppendedId(PhotoProvider.PHOTO_URI, photo.getId());
    }

    private void displayPhoto() {
        photo = dataLoader.getPhotoInDb(galleryId, page);

        if (photo != null) {
            String src = photo.getSrc();

            if (src != null && !src.isEmpty() && !photo.getInvalid()) {
                loadImage();
                return;
            } else {
                photo = null;
            }
        }

        callService();
    }

    private void callService() {
        Intent intent = new Intent(getActivity(), PhotoInfoService.class);
        isServiceCalled = true;

        intent.putExtra(PhotoInfoService.EXTRA_GALLERY, galleryId);
        intent.putExtra(PhotoInfoService.EXTRA_PAGE, page);

        getActivity().startService(intent);
    }

    private void loadImage() {
        if (photo.getDownloaded()) {
            File photoFile = photo.getFile();

            if (photoFile.exists()) {
                imageLoader.displayImage("file://" + photoFile.getAbsolutePath(), imageView, displayOptions,
                        imageLoadingListener, imageProgressListener);
                return;
            }
        }

        switch (pictureQuality) {
            case QUALITY_LOW:
            case QUALITY_MEDIUM:
            case QUALITY_HIGH:
                new PhotoLofiTask().execute(pictureQuality);
                break;

            default:
                displayImage(photo.getSrc());
        }
    }

    private void displayImage(String src) {
        imageLoader.displayImage(src, imageView, displayOptions, imageLoadingListener, imageProgressListener);
    }

    private void showRetryBtn() {
        progressBar.setVisibility(View.GONE);
        retryBtn.setVisibility(View.VISIBLE);
    }

    private SimpleImageLoadingListener imageLoadingListener = new SimpleImageLoadingListener() {
        private long startLoadAt;

        @Override
        public void onLoadingStarted(String imageUri, View view) {
            startLoadAt = System.currentTimeMillis();
            isLoaded = true;

            progressBar.setIndeterminate(false);
            progressBar.setProgress(0);
            getActivity().supportInvalidateOptionsMenu();
        }

        @Override
        public void onLoadingComplete(String imageUri, View view, Bitmap bitmap) {
            pageText.setVisibility(View.GONE);
            progressBar.setVisibility(View.GONE);
            imageView.setImageBitmap(bitmap);

            PhotoViewAttacher attacher = new PhotoViewAttacher(imageView);
            mBitmap = bitmap;
            attacher.setOnViewTapListener(onPhotoTap);

            getActivity().supportInvalidateOptionsMenu();

            BaseApplication.getTracker().send(MapBuilder.createTiming(
                    "resources", System.currentTimeMillis() - startLoadAt, "load photo", null
            ).build());
        }

        @Override
        public void onLoadingFailed(String imageUri, View view, FailReason failReason) {
            if (isServiceCalled) {
                showRetryBtn();
            } else {
                photo.setInvalid(true);
                photoDao.update(photo);
                callService();
            }
        }
    };

    private ImageLoadingProgressListener imageProgressListener = new ImageLoadingProgressListener() {
        @Override
        public void onProgressUpdate(String s, View view, int current, int total) {
            progressBar.setProgress((int) (current * 100f / total));
        }
    };

    private PhotoViewAttacher.OnViewTapListener onPhotoTap = new PhotoViewAttacher.OnViewTapListener() {
        @Override
        public void onViewTap(View view, float v, float v2) {
            ((PhotoActivity) getActivity()).toggleUIVisibility();
        }
    };

    @OnClick(R.id.retry)
    void onRetryBtnClick() {
        retryBtn.setVisibility(View.GONE);
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(true);

        if (photo != null) {
            photo.setInvalid(true);
            photoDao.update(photo);
        }

        callService();

        BaseApplication.getTracker().send(MapBuilder.createEvent(
                "UI", "button", "retry loading photo", null
        ).build());
    }

    private void setBookmark(boolean mark) {
        photo.setBookmarked(mark);
        photoDao.update(photo);

        if (mark) {
            showToast(R.string.notification_bookmark_added);
        } else {
            showToast(R.string.notification_bookmark_removed);
        }

        getActivity().supportInvalidateOptionsMenu();

        BaseApplication.getTracker().send(MapBuilder.createEvent(
                "UI", "button", "bookmark", null
        ).build());
    }

    private void showToast(int res) {
        Toast.makeText(getActivity(), res, Toast.LENGTH_SHORT).show();
    }

    private void setAsWallpaper() {
        Intent intent = new Intent(getActivity(), CropActivity.class);

        BaseApplication.getTracker().send(MapBuilder.createEvent(
                "UI", "button", "set as wallpaper", null
        ).build());

        intent.setData(getPhotoUri());
        startActivity(intent);
    }

    private void findSimilar() {
        Intent intent = new Intent(getActivity(), ImageSearchActivity.class);
        Bundle args = new Bundle();

        BaseApplication.getTracker().send(MapBuilder.createEvent(
                "UI", "button", "find similar", null
        ).build());

        args.putLong(ImageSearchActivity.EXTRA_PHOTO, photo.getId());
        intent.putExtras(args);
        startActivity(intent);
    }

    private void openInBrowser() {
        Intent intent = new Intent(Intent.ACTION_VIEW);

        BaseApplication.getTracker().send(MapBuilder.createEvent(
                "UI", "button", "open in browser", null
        ).build());

        intent.setData(photo.getUri());
        startActivity(intent);
    }

    private void savePhoto() {
        DialogFragment dialog = new PhotoSaveDialog();
        Bundle args = new Bundle();

        BaseApplication.getTracker().send(MapBuilder.createEvent(
                "UI", "button", "save photo", null
        ).build());

        args.putLong(PhotoSaveDialog.EXTRA_PHOTO, photo.getId());
        dialog.setArguments(args);
        dialog.show(getActivity().getSupportFragmentManager(), PhotoSaveDialog.TAG);
    }

    private void deletePhoto() {
        DialogFragment dialog = new PhotoDeleteConfirmDialog();
        Bundle args = new Bundle();

        BaseApplication.getTracker().send(MapBuilder.createEvent(
                "UI", "button", "delete photo", null
        ).build());

        args.putLong(PhotoDeleteConfirmDialog.EXTRA_PHOTO, photo.getId());
        dialog.setArguments(args);
        dialog.show(getActivity().getSupportFragmentManager(), PhotoDeleteConfirmDialog.TAG);
    }

    private class PhotoLofiTask extends AsyncTask<Integer, Integer, String> {
        @Override
        protected String doInBackground(Integer... integers) {
            try {
                int quality = integers[0];
                HttpContext httpContext = new BasicHttpContext();
                CookieStore cookieStore = new BasicCookieStore();

                cookieStore.addCookie(new Cookie("xres", Integer.toString(quality)));
                httpContext.setAttribute(ClientContext.COOKIE_STORE, cookieStore);

                HttpClient client = new DefaultHttpClient();
                HttpGet httpGet = new HttpGet(String.format(Constant.PHOTO_URL_LOFI,
                        photo.getToken(), photo.getGalleryId(), photo.getPage()));
                HttpResponse response = client.execute(httpGet, httpContext);
                String content = HttpRequestHelper.readResponse(response);
                Matcher matcher = pLofiSrc.matcher(content);
                String src = "";

                L.d("Photo lofi callback: %s", content);

                while (matcher.find()) {
                    src = matcher.group(1);
                }

                return src;
            } catch (IOException e) {
                e.printStackTrace();
            }

            return null;
        }

        @Override
        protected void onPostExecute(String src) {
            if (src != null && !src.isEmpty()) {
                displayImage(src);
            } else {
                showRetryBtn();
            }
        }
    }

    private class Cookie extends BasicClientCookie {
        private Cookie(String name, String value) {
            super(name, value);

            setPath("/");
            setDomain("lofi.e-hentai.org");
        }
    }
}