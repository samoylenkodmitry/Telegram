package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.collection.LongSparseArray;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Adapters.DialogsSearchAdapter;
import org.telegram.ui.Cells.ProfileSearchCell;
import org.telegram.ui.Cells.ShareDialogCell;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.DialogsActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class ShareLayout extends FrameLayout implements NotificationCenter.NotificationCenterDelegate {
    private final ArrayList<MessageObject>sendingMessageObjects;
    private final ChatActivity parentFragment;
    protected int currentAccount = UserConfig.selectedAccount;

    private RecyclerListView gridView;
    Theme.ResourcesProvider resourcesProvider;
    protected LongSparseArray<TLRPC.Dialog> selectedDialogs = new LongSparseArray<>();
    boolean darkTheme = false;
    ShareDialogsAdapter listAdapter;
    private View currentHoverCell;
    private WindowManager.LayoutParams windowParams;
    public ShareLayout(@NonNull Context context, Theme.ResourcesProvider resourcesProvider, MessageObject messageObject, ChatActivity parentFragment) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        this.parentFragment = parentFragment;
        sendingMessageObjects = new ArrayList<MessageObject>(Collections.singleton(messageObject));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Theme.getColor(Theme.key_dialogBackground, resourcesProvider));
        background.setCornerRadius(50f);
        setBackground(background);
        setClipToOutline(true);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), view.getHeight() / 2);
            }
        });
        gridView = new RecyclerListView(context, resourcesProvider) {

            @Override
            protected void onLayout(boolean changed, int l, int t, int r, int b) {
                super.onLayout(changed, l, t, r, b);
                if (changed) {
                    for (int i = 0; i < getChildCount(); i++) {
                        View child = getChildAt(i);
                        if (child.getVisibility() == VISIBLE && child.getTag() == null) {
                            child.setTag(true);
                            child.setScaleX(0);
                            child.setScaleY(0);
                            child.setAlpha(0);

                            long delay = Math.abs(getChildCount() / 2 - i) * 50L;
                            child.animate()
                                    .scaleX(1f)
                                    .scaleY(1f)
                                    .alpha(1f)
                                    .setDuration(250)
                                    .setStartDelay(delay)
                                    .setInterpolator(new OvershootInterpolator(1.02f))
                                    .start();
                        }
                    }
                }
            }
        };
        gridView.setSelectorDrawableColor(0);
        gridView.setItemSelectorColorProvider(i -> 0);
        gridView.setPadding(0, 0, 0, dp(48));
        gridView.setClipToPadding(false);
        gridView.setLayoutManager(new LinearLayoutManager(getContext(), LinearLayoutManager.HORIZONTAL, false));
        gridView.setHorizontalScrollBarEnabled(false);
        gridView.setVerticalScrollBarEnabled(false);

        gridView.setItemAnimator(new DefaultItemAnimator() {
            @Override
            public boolean animateAdd(RecyclerView.ViewHolder holder) {
                holder.itemView.setScaleX(0f);
                holder.itemView.setScaleY(0f);
                holder.itemView.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(300)
                        .setInterpolator(new DecelerateInterpolator())
                        
                        .start();
                return false;
            }
        });
        setScaleX(0.8f);
        setScaleY(0.8f);
        setAlpha(0);
        animate()
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(250)
                .setInterpolator(new OvershootInterpolator(1.02f))
                .start();
        gridView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        gridView.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(android.graphics.Rect outRect, View view, RecyclerView parent, RecyclerView.State state) {
                RecyclerListView.Holder holder = (RecyclerListView.Holder) parent.getChildViewHolder(view);
                if (holder != null) {
                    int pos = holder.getAdapterPosition();
                    outRect.left = pos == 0 ? 0 : dp(4);
                    outRect.right = pos == 4 ? 0 : dp(4);
                } else {
                    outRect.left = 0;
                    outRect.right = 0;
                }
            }
        });
        gridView.setAdapter(listAdapter = new ShareDialogsAdapter(context));
        gridView.setOnItemClickListener((view, position) -> {
            if (position < 0) {
                return;
            }
            TLRPC.Dialog dialog = listAdapter.getItem(position);
            if (dialog == null) {
                return;
            }
            selectDialog(view, dialog);
        });

        addView(gridView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT , LayoutHelper.MATCH_PARENT, Gravity.TOP | Gravity.LEFT, 0, 0, 0, 0));

        DialogsActivity.loadDialogs(AccountInstance.getInstance(currentAccount));
        if (listAdapter.dialogs.isEmpty()) {
            NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.dialogsNeedReload);
        }

        DialogsSearchAdapter.loadRecentSearch(currentAccount, 0, new DialogsSearchAdapter.OnRecentSearchLoaded() {
            @Override
            public void setRecentSearch(ArrayList<DialogsSearchAdapter.RecentSearchObject> arrayList, LongSparseArray<DialogsSearchAdapter.RecentSearchObject> hashMap) {
                if (arrayList != null) {
                    for (int i = 0; i < arrayList.size(); ++i) {
                        DialogsSearchAdapter.RecentSearchObject recentSearchObject = arrayList.get(i);
                        if (recentSearchObject.object instanceof TLRPC.Chat && !ChatObject.canWriteToChat((TLRPC.Chat) recentSearchObject.object)) {
                            arrayList.remove(i);
                            i--;
                        }
                    }
                }
            }
        });
        MediaDataController.getInstance(currentAccount).loadHints(true);

        AndroidUtilities.updateViewVisibilityAnimated(gridView, true, 1f, false);
        
        setPadding(dp(2), dp(4), dp(4), dp(2));
        setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));
    }

    public void dispatchMotionEvent(MotionEvent event) {
        if (!isAttachedToWindow()) return;
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                processTouch(event.getRawX(), event.getRawY());
                break;

            case MotionEvent.ACTION_MOVE:
                processTouch(event.getRawX(), event.getRawY());
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:

                if (currentHoverCell != null) {
                    View lastCell = currentHoverCell;
                    animateHoverScale(currentHoverCell, 1f);
                    currentHoverCell = null;

                    if (event.getAction() == MotionEvent.ACTION_UP) {
                        float[] point = mapPointToRecyclerView(event.getRawX(), event.getRawY());
                        View clickedView = gridView.findChildViewUnder(point[0], point[1]);
                        if (clickedView == lastCell) {
                            int position = gridView.getChildAdapterPosition(clickedView);
                            if (position >= 0) {
                                TLRPC.Dialog dialog = listAdapter.getItem(position);
                                if (dialog != null) {
                                    selectDialog(clickedView, dialog);
                                }
                            }
                        }
                    }
                }
                break;
        }
    }

    private boolean processTouch(float rawX, float rawY) {
        float[] point = mapPointToRecyclerView(rawX, rawY);

        // Get location of RecyclerView
        int[] location = new int[2];
        gridView.getLocationOnScreen(location);

        // Check if point is within RecyclerView bounds
        if (rawX >= location[0] && rawX <= location[0] + gridView.getWidth() &&
                rawY >= location[1] && rawY <= location[1] + gridView.getHeight()) {
            View newHoverCell = gridView.findChildViewUnder(point[0], point[1]);

            if (newHoverCell != currentHoverCell) {
                // Reset all cells to normal state
                for (int i = 0; i < gridView.getChildCount(); i++) {
                    View child = gridView.getChildAt(i);
                    if (child != newHoverCell && child instanceof ShareDialogCell) {
                        animateToTargetValues(child, 1f, 0.5f);
                    }
                }

                // Reset previous cell
                if (currentHoverCell != null) {
                    animateToTargetValues(currentHoverCell, 1f, 0.5f);
                }

                // Scale up new cell if it's valid
                if (newHoverCell instanceof ShareDialogCell) {
                    currentHoverCell = newHoverCell;
                    animateToTargetValues(currentHoverCell, 1.15f, 1f);
                } else {
                    currentHoverCell = null;
                }
            }
        } else {
            // Point is outside RecyclerView, reset all cells
            for (int i = 0; i < gridView.getChildCount(); i++) {
                View child = gridView.getChildAt(i);
                if (child instanceof ShareDialogCell) {
                    animateToTargetValues(child, 1f, 1f);
                }
            }
            currentHoverCell = null;
        }
        
        if (currentHoverCell != null) {
            View nameView = ((ShareDialogCell) currentHoverCell).nameTextView;
        }

        return true;
    }

    private void animateToTargetValues(View view, float targetScale, float targetAlpha) {
        if (view == null) return;

        float currentScale = view.getScaleX(); // Assuming X and Y scales are always the same
        float currentAlpha = view.getAlpha();

        // Only animate if we haven't reached target values
        if (currentScale != targetScale || currentAlpha != targetAlpha) {
            // If animation is already running towards these values, let it continue
            if (view.getTag(R.id.tag_scale) == null ||
                    (float)view.getTag(R.id.tag_scale) != targetScale ||
                    (float)view.getTag(R.id.tag_alpha) != targetAlpha) {

                // Store target values to check in future calls
                view.setTag(R.id.tag_scale, targetScale);
                view.setTag(R.id.tag_alpha, targetAlpha);

                view.animate()
                        .scaleX(targetScale)
                        .scaleY(targetScale)
                        .alpha(targetAlpha)
                        .setDuration(250)
                        .setInterpolator(CubicBezierInterpolator.DEFAULT)
                        .setUpdateListener(animation -> {
                            // Clear tags when animation completes
                            if (animation.getAnimatedFraction() >= 1f) {
                                view.setTag(R.id.tag_scale, null);
                                view.setTag(R.id.tag_alpha, null);
                            }
                        });
            }
        }
    }

    private void animateHoverScale(View view, float scale) {
        if (view == null) return;
        view.animate()
                .scaleX(scale)
                .scaleY(scale)
                .alpha(scale == 1f ? 1f : 0.5f)
                .setDuration(350)
                .setInterpolator(CubicBezierInterpolator.DEFAULT)
                .start();
    }

    private float[] mapPointToRecyclerView(float rawX, float rawY) {
        int[] location = new int[2];
        gridView.getLocationOnScreen(location);
        return new float[] {
                rawX - location[0],
                rawY - location[1]
        };
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        GradientDrawable background = (GradientDrawable) getBackground();
        background.setCornerRadius(h / 2f);
        super.onSizeChanged(w, h, oldw, oldh);
    }
    
    private RecyclerListView getMainGridView() {
        return gridView;
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.dialogsNeedReload) {
            if (listAdapter != null) {
                listAdapter.fetchDialogs();
            }
            NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.dialogsNeedReload);
        }
    }

    private class ShareDialogsAdapter extends RecyclerListView.SelectionAdapter {

        private class MyStoryDialog extends TLRPC.Dialog {
            { id = Long.MAX_VALUE; }
        }

        private Context context;
        private int currentCount;
        private ArrayList<TLRPC.Dialog> dialogs = new ArrayList<>();
        private LongSparseArray<TLRPC.Dialog> dialogsMap = new LongSparseArray<>();

        public ShareDialogsAdapter(Context context) {
            this.context = context;
            fetchDialogs();
        }

        public void fetchDialogs() {
            dialogs.clear();
            dialogsMap.clear();
            long selfUserId = UserConfig.getInstance(currentAccount).clientUserId;
            if (false) {
                MyStoryDialog d = new MyStoryDialog();
                dialogs.add(d);
                dialogsMap.put(d.id, d);
            }
            if (!MessagesController.getInstance(currentAccount).dialogsForward.isEmpty()) {
                TLRPC.Dialog dialog = MessagesController.getInstance(currentAccount).dialogsForward.get(0);
                dialogs.add(dialog);
                dialogsMap.put(dialog.id, dialog);
            }
            ArrayList<TLRPC.Dialog> archivedDialogs = new ArrayList<>();
            ArrayList<TLRPC.Dialog> allDialogs = MessagesController.getInstance(currentAccount).getAllDialogs();
            for (int a = 0; a < allDialogs.size(); a++) {
                TLRPC.Dialog dialog = allDialogs.get(a);
                if (!(dialog instanceof TLRPC.TL_dialog)) {
                    continue;
                }
                if (dialog.id == selfUserId) {
                    continue;
                }
                if (!DialogObject.isEncryptedDialog(dialog.id)) {
                    if (DialogObject.isUserDialog(dialog.id)) {
                        if (dialog.folder_id == 1) {
                            archivedDialogs.add(dialog);
                        } else {
                            dialogs.add(dialog);
                        }
                        dialogsMap.put(dialog.id, dialog);
                    } else {
                        TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-dialog.id);
                        if (!(chat == null || ChatObject.isNotInChat(chat) || chat.gigagroup && !ChatObject.hasAdminRights(chat) || ChatObject.isChannel(chat) && !chat.creator && (chat.admin_rights == null || !chat.admin_rights.post_messages) && !chat.megagroup)) {
                            if (dialog.folder_id == 1) {
                                archivedDialogs.add(dialog);
                            } else {
                                dialogs.add(dialog);
                            }
                            dialogsMap.put(dialog.id, dialog);
                        }
                    }
                }
            }
            dialogs.addAll(archivedDialogs);
            notifyDataSetChanged();
        }

        @Override
        public int getItemCount() {
            int count = Math.min(5, dialogs.size());
            if (count != 0) {
                count++;
            }
            return count;
        }

        public TLRPC.Dialog getItem(int position) {
            position--;
            if (position < 0 || position >= dialogs.size()) {
                return null;
            }
            return dialogs.get(position);
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            if (holder.getItemViewType() == 1) {
                return false;
            }
            return true;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case 0: {
                    view = new ShareDialogCell(context, darkTheme ? ShareDialogCell.TYPE_CALL : ShareDialogCell.TYPE_SHARE, resourcesProvider) {
                        @Override
                        protected String repostToCustomName() {
                            if (true /*includeStoryFromMessage*/) {
                                return LocaleController.getString(R.string.RepostToStory);
                            }
                            return super.repostToCustomName();
                        }
                    };
                    view.setLayoutParams(new RecyclerView.LayoutParams(dp(70), dp(50)));
                    break;
                }
                case 1:
                default: {
                    view = new View(context);
                    view.setLayoutParams(new RecyclerView.LayoutParams(dp(1), dp(darkTheme ? 59 : 46)));
                    break;
                }
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            if (holder.getItemViewType() == 0) {
                ShareDialogCell cell = (ShareDialogCell) holder.itemView;
                TLRPC.Dialog dialog = getItem(position);
                if (dialog == null) return;
                cell.setDialog(dialog.id, selectedDialogs.indexOfKey(dialog.id) >= 0, null);
            }
        }
        

        @Override
        public int getItemViewType(int position) {
            if (position == 0) {
                return 1;
            }
            return 0;
        }
    }

    private void selectDialog(View cell, TLRPC.Dialog dialog) {
        /*
        if (dialog instanceof ShareDialogsAdapter.MyStoryDialog) {
            onShareStory(cell);
            return;
        }
         */

        if (DialogObject.isChatDialog(dialog.id)) {
            TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-dialog.id);
            if (ChatObject.isChannel(chat) && !chat.megagroup && (!ChatObject.isCanWriteToChannel(-dialog.id, currentAccount)/* || hasPoll == 2*/)) {
                /*
                AlertDialog.Builder builder = new AlertDialog.Builder(parentActivity);
                builder.setTitle(LocaleController.getString(R.string.SendMessageTitle));
                if (hasPoll == 2) {
                    if (isChannel) {
                        builder.setMessage(LocaleController.getString(R.string.PublicPollCantForward));
                    } else if (ChatObject.isActionBannedByDefault(chat, ChatObject.ACTION_SEND_POLLS)) {
                        builder.setMessage(LocaleController.getString(R.string.ErrorSendRestrictedPollsAll));
                    } else {
                        builder.setMessage(LocaleController.getString(R.string.ErrorSendRestrictedPolls));
                    }
                } else {
                    builder.setMessage(LocaleController.getString(R.string.ChannelCantSendMessage));
                }
                builder.setNegativeButton(LocaleController.getString(R.string.OK), null);
                builder.show();
                
                 */
                return;
            }
        } else if (DialogObject.isEncryptedDialog(dialog.id) && (false/*hasPoll != 0*/)) {
            /*
            AlertDialog.Builder builder = new AlertDialog.Builder(parentActivity);
            builder.setTitle(LocaleController.getString(R.string.SendMessageTitle));
            if (hasPoll != 0) {
                builder.setMessage(LocaleController.getString(R.string.PollCantForwardSecretChat));
            } else {
                builder.setMessage(LocaleController.getString(R.string.InvoiceCantForwardSecretChat));
            }
            builder.setNegativeButton(LocaleController.getString(R.string.OK), null);
            builder.show();
            
             */
            return;
        }
        if (selectedDialogs.indexOfKey(dialog.id) >= 0) {
            selectedDialogs.remove(dialog.id);
            if (cell instanceof ProfileSearchCell) {
                ((ProfileSearchCell) cell).setChecked(false, true);
            } else if (cell instanceof ShareDialogCell) {
                ((ShareDialogCell) cell).setChecked(false, true);
            }

        } else {
            if (DialogObject.isChatDialog(dialog.id) && MessagesController.getInstance(currentAccount).getChat(-dialog.id) != null && MessagesController.getInstance(currentAccount).getChat(-dialog.id).forum) {
                AtomicReference<Runnable> timeoutRef = new AtomicReference<>();
                NotificationCenter.NotificationCenterDelegate delegate = new NotificationCenter.NotificationCenterDelegate() {
                    @SuppressLint("NotifyDataSetChanged")
                    @Override
                    public void didReceivedNotification(int id, int account, Object... args) {
                    }
                };
                timeoutRef.set(() -> {
                    timeoutRef.set(null);
                    delegate.didReceivedNotification(NotificationCenter.topicsDidLoaded, currentAccount, -dialog.id);
                });
                NotificationCenter.getInstance(currentAccount).addObserver(delegate, NotificationCenter.topicsDidLoaded);
                if (MessagesController.getInstance(currentAccount).getTopicsController().getTopics(-dialog.id) != null) {
                    delegate.didReceivedNotification(NotificationCenter.topicsDidLoaded, currentAccount, -dialog.id);
                } else {
                    MessagesController.getInstance(currentAccount).getTopicsController().loadTopics(-dialog.id);
                    AndroidUtilities.runOnUIThread(timeoutRef.get(), 300);
                }
                return;
            }

            selectedDialogs.put(dialog.id, dialog);
            /*
            if (cell instanceof ProfileSearchCell) {
                ((ProfileSearchCell) cell).setChecked(true, true);
            } else if (cell instanceof ShareDialogCell) {
                ((ShareDialogCell) cell).setChecked(true, true);
            }
            
             */

            long selfUserId = UserConfig.getInstance(currentAccount).clientUserId;
            sendInternal(false);
        }
    }

    protected void sendInternal(boolean withSound) {
        for (int a = 0; a < selectedDialogs.size(); a++) {
            long key = selectedDialogs.keyAt(a);
            /*
            if (AlertsCreator.checkSlowMode(getContext(), currentAccount, key, frameLayout2.getTag() != null && commentTextView.length() > 0)) {
                return;
            }
            
             */
        }

        CharSequence[] text = new CharSequence[] { "" };
        ArrayList<TLRPC.MessageEntity> entities = MediaDataController.getInstance(currentAccount).getEntities(text, true);
        if (sendingMessageObjects != null) {
            List<Long> removeKeys = new ArrayList<>();
            for (int a = 0; a < selectedDialogs.size(); a++) {
                long key = selectedDialogs.keyAt(a);
                MessageObject replyTopMsg = null;
                if (replyTopMsg != null) {
                    replyTopMsg.isTopicMainMessage = true;
                }
                int result = SendMessagesHelper.getInstance(currentAccount).sendMessage(sendingMessageObjects, key, true,false, withSound, 0, replyTopMsg);
                if (result != 0) {
                    removeKeys.add(key);
                }
                if (selectedDialogs.size() == 1) {
                    AlertsCreator.showSendMediaAlert(result, parentFragment, null);

                    if (result != 0) {
                        break;
                    }
                }
            }
            for (long key : removeKeys) {
                TLRPC.Dialog dialog = selectedDialogs.get(key);
                selectedDialogs.remove(key);
            }
            if (!selectedDialogs.isEmpty()) {
                //onSend(selectedDialogs, sendingMessageObjects.size(), selectedDialogs.size() == 1 ? selectedDialogTopics.get(selectedDialogs.valueAt(0)) : null);
            }
        } else {

        }
        /*
        if (delegate != null) {
            delegate.didShare();
        }
        dismiss();
        
         */
        onSend();
    }
    public void onSend() {
        
    }
}
