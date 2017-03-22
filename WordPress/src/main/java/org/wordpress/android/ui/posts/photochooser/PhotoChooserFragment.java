package org.wordpress.android.ui.posts.photochooser;

import android.app.Fragment;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.ActivityOptionsCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.view.ActionMode;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import org.wordpress.android.R;
import org.wordpress.android.ui.posts.photochooser.PhotoChooserAdapter.PhotoChooserAdapterListener;
import org.wordpress.android.util.AniUtils;
import org.wordpress.android.util.AppLog;

import java.util.ArrayList;
import java.util.List;

public class PhotoChooserFragment extends Fragment {

    static final int NUM_COLUMNS = 3;

    public enum PhotoChooserIcon {
        ANDROID_CAMERA,
        ANDROID_PICKER,
        WP_MEDIA
    }

    /*
     * parent activity must implement this listener
     */
    public interface PhotoChooserListener {
        void onPhotoChooserMediaChosen(@NonNull List<Uri> uriList);
        void onPhotoChooserIconClicked(@NonNull PhotoChooserIcon icon);
    }

    private RecyclerView mRecycler;
    private PhotoChooserAdapter mAdapter;
    private View mBottomBar;
    private ActionMode mActionMode;
    private GridLayoutManager mGridManager;
    private Parcelable mRestoreState;
    private PhotoChooserListener mListener;

    public static PhotoChooserFragment newInstance(@NonNull PhotoChooserListener listener) {
        Bundle args = new Bundle();
        PhotoChooserFragment fragment = new PhotoChooserFragment();
        fragment.setPhotoChooserListener(listener);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.photo_chooser_fragment, container, false);

        mRecycler = (RecyclerView) view.findViewById(R.id.recycler);
        mRecycler.setHasFixedSize(true);

        mBottomBar = view.findViewById(R.id.bottom_bar);
        mBottomBar.findViewById(R.id.icon_camera).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mListener != null) {
                    mListener.onPhotoChooserIconClicked(PhotoChooserIcon.ANDROID_CAMERA);
                }
            }
        });
        mBottomBar.findViewById(R.id.icon_picker).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mListener != null) {
                    mListener.onPhotoChooserIconClicked(PhotoChooserIcon.ANDROID_PICKER);
                }
            }
        });
        mBottomBar.findViewById(R.id.icon_wpmedia).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mListener != null) {
                    mListener.onPhotoChooserIconClicked(PhotoChooserIcon.WP_MEDIA);
                }
            }
        });

        reload();

        return view;
    }

    private void setPhotoChooserListener(PhotoChooserListener listener) {
        mListener = listener;
    }

    private void showBottomBar() {
        if (!isBottomBarShowing()) {
            AniUtils.animateBottomBar(mBottomBar, true);
        }
    }

    private void hideBottomBar() {
        if (isBottomBarShowing()) {
            AniUtils.animateBottomBar(mBottomBar, false);
        }
    }

    private boolean isBottomBarShowing() {
        return mBottomBar.getVisibility() == View.VISIBLE;
    }

    /*
     *   - single tap adds the photo to post or selects it if multi-select is enabled
     *   - double tap previews the photo
     *   - long press enables multi-select
     */
    private final PhotoChooserAdapterListener mAdapterListener = new PhotoChooserAdapterListener() {
        @Override
        public void onItemTapped(Uri mediaUri) {
            if (mListener != null) {
                List<Uri> uriList = new ArrayList<>();
                uriList.add(mediaUri);
                mListener.onPhotoChooserMediaChosen(uriList);
            }
        }

        @Override
        public void onItemDoubleTapped(View view, Uri mediaUri) {
            showPreview(view, mediaUri);
        }

        @Override
        public void onSelectedCountChanged(int count) {
            if (count == 0) {
                finishActionMode();
            } else {
                if (mActionMode == null) {
                    ((AppCompatActivity) getActivity()).startSupportActionMode(new ActionModeCallback());
                }
                updateActionModeTitle();
            }
        }

        @Override
        public void onAdapterLoaded(boolean isEmpty) {
            showEmptyView(isEmpty);
            if (mRestoreState != null) {
                mGridManager.onRestoreInstanceState(mRestoreState);
                mRestoreState = null;
            }
        }
    };

    private void showEmptyView(boolean show) {
        if (isAdded()) {
            getView().findViewById(R.id.text_empty).setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    /*
     * shows full-screen preview of the passed media
     */
    private void showPreview(View sourceView, Uri mediaUri) {
        boolean isVideo = getAdapter().isVideoUri(mediaUri);
        Intent intent = new Intent(getActivity(), PhotoChooserPreviewActivity.class);
        intent.putExtra(PhotoChooserPreviewActivity.ARG_MEDIA_URI, mediaUri.toString());
        intent.putExtra(PhotoChooserPreviewActivity.ARG_IS_VIDEO, isVideo);

        int startWidth = sourceView.getWidth();
        int startHeight = sourceView.getHeight();
        int startX = startWidth / 2;
        int startY = startHeight / 2;

        ActivityOptionsCompat options = ActivityOptionsCompat.makeScaleUpAnimation(
                sourceView,
                startX,
                startY,
                startWidth,
                startHeight);
        ActivityCompat.startActivity(getActivity(), intent, options.toBundle());
    }

    private PhotoChooserAdapter getAdapter() {
        if (mAdapter == null) {
            mAdapter = new PhotoChooserAdapter(getActivity(), mAdapterListener);
        }
        return mAdapter;
    }

    /*
     * populates the adapter with media stored on the device
     */
    public void reload() {
        if (!isAdded()) {
            AppLog.w(AppLog.T.POSTS, "Photo chooser > can't reload when not added");
            return;
        }

        // save the current state so we can restore it after loading
        if (mGridManager != null) {
            mRestoreState = mGridManager.onSaveInstanceState();
        }

        mGridManager = new GridLayoutManager(getActivity(), NUM_COLUMNS);
        mRecycler.setLayoutManager(mGridManager);
        mRecycler.setAdapter(getAdapter());
        getAdapter().refresh(true);
    }

    /*
     * similar to the above but only repopulates if changes are detected
     */
    public void refresh() {
        if (!isAdded()) {
            AppLog.w(AppLog.T.POSTS, "Photo chooser > can't refresh when not added");
            return;
        }
        if (mGridManager == null || mAdapter == null) {
            reload();
        } else {
            getAdapter().refresh(false);
        }
    }

    public void finishActionMode() {
        if (mActionMode != null) {
            mActionMode.finish();
        }
    }

    private void updateActionModeTitle() {
        if (mActionMode == null) return;

        int numSelected = getAdapter().getNumSelected();
        String title = String.format(getString(R.string.cab_selected), numSelected);
        mActionMode.setTitle(title);
    }

    private final class ActionModeCallback implements ActionMode.Callback {
        @Override
        public boolean onCreateActionMode(ActionMode actionMode, Menu menu) {
            mActionMode = actionMode;
            MenuInflater inflater = actionMode.getMenuInflater();
            inflater.inflate(R.menu.photo_chooser_action_mode, menu);
            hideBottomBar();
            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            return false;
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            if (item.getItemId() == R.id.mnu_confirm_selection && mListener != null) {
                ArrayList<Uri> uriList = getAdapter().getSelectedURIs();
                mListener.onPhotoChooserMediaChosen(uriList);
                return true;
            }
            return false;
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            getAdapter().setMultiSelectEnabled(false);
            mActionMode = null;
            showBottomBar();
        }
    }
}
