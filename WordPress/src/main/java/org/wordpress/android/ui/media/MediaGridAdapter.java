package org.wordpress.android.ui.media;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.os.AsyncTask;
import android.os.Handler;
import android.support.v7.widget.RecyclerView;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.android.volley.toolbox.ImageLoader;
import com.wellsql.generated.MediaModelTable;

import org.wordpress.android.R;
import org.wordpress.android.WordPress;
import org.wordpress.android.fluxc.model.SiteModel;
import org.wordpress.android.models.MediaUploadState;
import org.wordpress.android.ui.FadeInNetworkImageView;
import org.wordpress.android.util.AniUtils;
import org.wordpress.android.util.DisplayUtils;
import org.wordpress.android.util.ImageUtils.BitmapWorkerCallback;
import org.wordpress.android.util.ImageUtils.BitmapWorkerTask;
import org.wordpress.android.util.MediaUtils;
import org.wordpress.android.util.PhotonUtils;
import org.wordpress.android.util.SiteUtils;
import org.wordpress.android.util.StringUtils;
import org.wordpress.android.util.UrlUtils;

import java.util.ArrayList;

/**
 * An adapter for the media gallery grid.
 */
public class MediaGridAdapter extends RecyclerView.Adapter<MediaGridAdapter.GridViewHolder> {
    private MediaGridAdapterCallback mCallback;
    private boolean mHasRetrievedAll;

    private boolean mAllowMultiselect;
    private boolean mInMultiSelect;

    private final Handler mHandler;
    private final LayoutInflater mInflater;

    private ImageLoader mImageLoader;
    private final Context mContext;
    private final SiteModel mSite;
    private Cursor mCursor;

    private final int mThumbWidth;
    private final int mThumbHeight;

    // Must be an ArrayList (order is important for galleries)
    private ArrayList<Integer> mSelectedItems;

    private static final float SCALE_NORMAL = 1.0f;
    private static final float SCALE_SELECTED = .85f;

    public interface MediaGridAdapterCallback {
        void onAdapterFetchMoreData();
        void onAdapterRetryUpload(int localMediaId);
        void onAdapterItemSelected(int position);
        void onAdapterSelectionCountChanged(int count);
    }

    private static final int INVALID_POSITION = -1;

    public MediaGridAdapter(Context context, SiteModel site, ImageLoader imageLoader) {
        super();
        setHasStableIds(true);

        mContext = context;
        mSite = site;
        mSelectedItems = new ArrayList<>();
        mInflater = LayoutInflater.from(context);
        mHandler = new Handler();

        int displayWidth = DisplayUtils.getDisplayPixelWidth(mContext);
        mThumbWidth = displayWidth / getColumnCount(mContext);
        mThumbHeight = (int) (mThumbWidth * 0.75f);

        setImageLoader(imageLoader);
    }

    @Override
    public long getItemId(int position) {
        return getLocalMediaIdAtPosition(position);
    }

    private void setImageLoader(ImageLoader imageLoader) {
        mImageLoader = imageLoader;
    }

    public void setCursor(Cursor cursor) {
        mCursor = cursor;
        notifyDataSetChanged();
    }

    @Override
    public GridViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = mInflater.inflate(R.layout.media_grid_item, parent, false);
        return new GridViewHolder(view);
    }

    @Override
    public void onBindViewHolder(GridViewHolder holder, int position) {
        if (!isValidPosition(position)) {
            return;
        }

        mCursor.moveToPosition(position);
        holder.imageView.setTag(null);

        final int localMediaId = mCursor.getInt(mCursor.getColumnIndex(MediaModelTable.ID));

        String state = mCursor.getString(mCursor.getColumnIndex(MediaModelTable.UPLOAD_STATE));
        String filePath = mCursor.getString(mCursor.getColumnIndex(MediaModelTable.FILE_PATH));
        String mimeType = StringUtils.notNullStr(mCursor.getString(mCursor.getColumnIndex(MediaModelTable.MIME_TYPE)));

        boolean isLocalFile = MediaUtils.isLocalFile(state);
        boolean isSelected = isItemSelected(localMediaId);
        boolean isImage = mimeType.startsWith("image/");

        if (isImage) {
            holder.fileContainer.setVisibility(View.GONE);
            if (isLocalFile) {
                loadLocalImage(filePath, holder.imageView);
            } else {
                String imageUrl = mCursor.getString(mCursor.getColumnIndex(MediaModelTable.URL));
                String thumbUrl;
                // if this isn't a private site use Photon to request the image at the exact size,
                // otherwise append the standard wp query params to request the desired size
                if (SiteUtils.isPhotonCapable(mSite)) {
                    thumbUrl = PhotonUtils.getPhotonImageUrl(imageUrl, mThumbWidth, mThumbHeight);
                } else {
                    thumbUrl = UrlUtils.removeQuery(imageUrl) + "?w=" + mThumbWidth + "&h=" + mThumbHeight;
                }
                WordPressMediaUtils.loadNetworkImage(thumbUrl, holder.imageView, mImageLoader);
            }
        } else {
            // not an image, so show file name and file type
            holder.imageView.setImageDrawable(null);
            String fileName = mCursor.getString(mCursor.getColumnIndex(MediaModelTable.FILE_NAME));
            String title = mCursor.getString(mCursor.getColumnIndex(MediaModelTable.TITLE));
            String fileExtension = MediaUtils.getExtensionForMimeType(mimeType);
            holder.fileContainer.setVisibility(View.VISIBLE);
            holder.titleView.setText(TextUtils.isEmpty(title) ? fileName : title);
            holder.fileTypeView.setText(fileExtension.toUpperCase());
            int placeholderResId = WordPressMediaUtils.getPlaceholder(fileName);
            holder.fileTypeImageView.setImageResource(placeholderResId);
        }

        // show selection count when selected
        holder.selectionCountTextView.setVisibility(isSelected ? View.VISIBLE : View.GONE);
        if (isSelected) {
            int count = mSelectedItems.indexOf(localMediaId) + 1;
            holder.selectionCountTextView.setText(Integer.toString(count));
        }

        // make sure the thumbnail scale reflects its selection state
        float scale = isSelected ? SCALE_SELECTED : SCALE_NORMAL;
        if (holder.imageView.getScaleX() != scale) {
            holder.imageView.setScaleX(scale);
            holder.imageView.setScaleY(scale);
        }

        // show upload state unless it's already uploaded
        if (!TextUtils.isEmpty(state) && !state.equalsIgnoreCase(MediaUploadState.UPLOADED.name())) {
            holder.stateContainer.setVisibility(View.VISIBLE);
            holder.stateTextView.setText(state);

            // hide progressbar and add onclick to retry failed uploads
            if (state.equalsIgnoreCase(MediaUploadState.FAILED.name())) {
                holder.progressUpload.setVisibility(View.GONE);
                holder.stateTextView.setText(mContext.getString(R.string.retry));
                holder.stateTextView.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.media_retry_image, 0, 0);
                holder.stateTextView.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (!isInMultiSelect()) {
                            ((TextView) v).setText(R.string.upload_queued);
                            ((TextView) v).setCompoundDrawables(null, null, null, null);
                            v.setOnClickListener(null);
                            if (mCallback != null) {
                                mCallback.onAdapterRetryUpload(localMediaId);
                            }
                        }
                    }
                });
            } else {
                holder.progressUpload.setVisibility(View.VISIBLE);
                holder.stateTextView.setOnClickListener(null);
                holder.stateTextView.setCompoundDrawables(null, null, null, null);
            }
        } else {
            holder.stateContainer.setVisibility(View.GONE);
            holder.stateContainer.setOnClickListener(null);
        }

        // if we are near the end, make a call to fetch more
        if (position == getItemCount() - 1
                && !mHasRetrievedAll
                && mCallback != null) {
            mCallback.onAdapterFetchMoreData();
        }
    }

    public ArrayList<Integer> getSelectedItems() {
        return mSelectedItems;
    }

    public int getSelectedItemCount() {
        return mSelectedItems.size();
    }

    class GridViewHolder extends RecyclerView.ViewHolder {
        private final TextView titleView;
        private final FadeInNetworkImageView imageView;
        private final TextView fileTypeView;
        private final ImageView fileTypeImageView;
        private final TextView selectionCountTextView;
        private final TextView stateTextView;
        private final ProgressBar progressUpload;
        private final ViewGroup stateContainer;
        private final ViewGroup fileContainer;

        public GridViewHolder(View view) {
            super(view);

            imageView = (FadeInNetworkImageView) view.findViewById(R.id.media_grid_item_image);
            selectionCountTextView = (TextView) view.findViewById(R.id.text_selection_count);

            stateContainer = (ViewGroup) view.findViewById(R.id.media_grid_item_upload_state_container);
            stateTextView = (TextView) stateContainer.findViewById(R.id.media_grid_item_upload_state);
            progressUpload = (ProgressBar) stateContainer.findViewById(R.id.media_grid_item_upload_progress);

            fileContainer = (ViewGroup) view.findViewById(R.id.media_grid_item_file_container);
            titleView = (TextView) fileContainer.findViewById(R.id.media_grid_item_name);
            fileTypeView = (TextView) fileContainer.findViewById(R.id.media_grid_item_filetype);
            fileTypeImageView = (ImageView) fileContainer.findViewById(R.id.media_grid_item_filetype_image);

            // make the progress bar white
            progressUpload.getIndeterminateDrawable().setColorFilter(Color.WHITE, PorterDuff.Mode.MULTIPLY);

            // set size of image and container views
            imageView.getLayoutParams().width = mThumbWidth;
            imageView.getLayoutParams().height = mThumbHeight;
            stateContainer.getLayoutParams().width = mThumbWidth;
            stateContainer.getLayoutParams().height = mThumbHeight;
            fileContainer.getLayoutParams().width = mThumbWidth;
            fileContainer.getLayoutParams().height = mThumbHeight;

            itemView.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    int position = getAdapterPosition();
                    if (isInMultiSelect()) {
                        toggleItemSelected(GridViewHolder.this, position);
                    } else if (mCallback != null) {
                        mCallback.onAdapterItemSelected(position);
                    }
                }
            });

            itemView.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    int position = getAdapterPosition();
                    if (isInMultiSelect()) {
                        toggleItemSelected(GridViewHolder.this, position);
                    } else if (mAllowMultiselect) {
                        setInMultiSelect(true);
                        setItemSelectedByPosition(GridViewHolder.this, position, true);
                    }
                    return true;
                }
            });
        }
    }

    public void setAllowMultiselect(boolean allow) {
        mAllowMultiselect = allow;
    }

    public boolean isInMultiSelect() {
        return mInMultiSelect;
    }

    public void setInMultiSelect(boolean value) {
        if (mInMultiSelect != value) {
            mInMultiSelect = value;
            clearSelection();
        }
    }
    private boolean isValidPosition(int position) {
        return position >= 0 && position < getItemCount();
    }
    public int getLocalMediaIdAtPosition(int position) {
        if (isValidPosition(position)) {
            mCursor.moveToPosition(position);
            return mCursor.getInt(mCursor.getColumnIndex(MediaModelTable.ID));
        }
        return INVALID_POSITION;
    }

    private void loadLocalImage(final String filePath, ImageView imageView) {
        imageView.setTag(filePath);

        Bitmap bitmap = WordPress.getBitmapCache().get(filePath);
        if (bitmap != null) {
            imageView.setImageBitmap(bitmap);
        } else {
            imageView.setImageBitmap(null);
            new BitmapWorkerTask(imageView, mThumbWidth, mThumbHeight, new BitmapWorkerCallback() {
                @Override
                public void onBitmapReady(final String path, final ImageView imageView, final Bitmap bitmap) {
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            WordPress.getBitmapCache().put(path, bitmap);
                            if (imageView != null
                                    && imageView.getTag() instanceof String
                                    && ((String)imageView.getTag()).equalsIgnoreCase(path)) {
                                imageView.setImageBitmap(bitmap);
                            }
                        }
                    });
                }
            }).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, filePath);
        }
    }

    @Override
    public int getItemCount() {
        return mCursor != null ? mCursor.getCount() : 0;
    }

    public static int getColumnCount(Context context) {
        return context.getResources().getInteger(R.integer.media_grid_num_columns);
    }

    public void setCallback(MediaGridAdapterCallback callback) {
        mCallback = callback;
    }

    public void setHasRetrievedAll(boolean b) {
        mHasRetrievedAll = b;
    }

    public void clearSelection() {
        if (mSelectedItems.size() > 0) {
            mSelectedItems.clear();
            notifyDataSetChanged();
        }
    }

    public boolean isItemSelected(int localMediaId) {
        return mSelectedItems.contains(localMediaId);
    }

    public void removeSelectionByLocalId(int localMediaId) {
        if (isItemSelected(localMediaId)) {
            mSelectedItems.remove(Integer.valueOf(localMediaId));
            if (mCallback != null) {
                mCallback.onAdapterSelectionCountChanged(mSelectedItems.size());
            }
            notifyDataSetChanged();
        }
    }

    private void setItemSelectedByPosition(GridViewHolder holder, int position, boolean selected) {
        if (mCursor == null || !isValidPosition(position)) {
            return;
        }

        mCursor.moveToPosition(position);
        int columnIndex = mCursor.getColumnIndex(MediaModelTable.ID);
        if (columnIndex == -1) {
            return;
        }

        int localMediaId = mCursor.getInt(columnIndex);
        if (selected) {
            mSelectedItems.add(localMediaId);
        } else {
            mSelectedItems.remove(Integer.valueOf(localMediaId));
        }

        // show and animate the count
        if (selected) {
            holder.selectionCountTextView.setText(Integer.toString(mSelectedItems.indexOf(localMediaId) + 1));
        }
        AniUtils.startAnimation(holder.selectionCountTextView,
                selected ? R.anim.cab_select : R.anim.cab_deselect);
        holder.selectionCountTextView.setVisibility(selected ? View.VISIBLE : View.GONE);

        // scale the thumbnail
        if (selected) {
            AniUtils.scale(holder.imageView, SCALE_NORMAL, SCALE_SELECTED, AniUtils.Duration.SHORT);
        } else {
            AniUtils.scale(holder.imageView, SCALE_SELECTED, SCALE_NORMAL, AniUtils.Duration.SHORT);
        }

        // redraw after the scale animation completes
        long delayMs = AniUtils.Duration.SHORT.toMillis(mContext);
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                notifyDataSetChanged();
            }
        }, delayMs);

        if (mCallback != null) {
            mCallback.onAdapterSelectionCountChanged(mSelectedItems.size());
        }
    }

    private void toggleItemSelected(GridViewHolder holder, int position) {
        if (mCursor == null || !isValidPosition(position)) {
            return;
        }
        mCursor.moveToPosition(position);
        int columnIndex = mCursor.getColumnIndex(MediaModelTable.ID);
        if (columnIndex != -1) {
            int localMediaId = mCursor.getInt(columnIndex);
            boolean isSelected = mSelectedItems.contains(localMediaId);
            setItemSelectedByPosition(holder, position, !isSelected);
        }
    }

    public void setSelectedItems(ArrayList<Integer> selectedItems) {
        mSelectedItems = selectedItems;
        if (mCallback != null) {
            mCallback.onAdapterSelectionCountChanged(mSelectedItems.size());
        }
        notifyDataSetChanged();
    }
}
