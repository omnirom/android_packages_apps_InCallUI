package com.android.incallui;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.AccelerateInterpolator;
import android.view.View;
import android.view.WindowManager;
import android.view.ViewConfiguration;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.incallui.ContactInfoCache.ContactCacheEntry;
import com.android.incallui.ContactInfoCache.ContactInfoCacheCallback;
import com.android.services.telephony.common.CallIdentification;
import com.android.services.telephony.common.Call;

/**
 * Handles the call card activity that pops up when a call
 * arrives
 */
public class InCallCardActivity extends Activity implements GlowPadWrapper.AnswerListener {
    private static final int SLIDE_IN_DURATION_MS = 500;
    private static final int SLIDE_OUT_DURATION_MS = 300;
    private static final int SLIDE_SWIPE_DURATION_MS = 200;

    private TextView mNameTextView;
    private ImageView mContactImage;
    private Handler mHandler = new Handler();
    private Call mCall;
    private LinearLayout mCard;
    private float mStartY;
    private int mSlop;
    private int mCardHeight;

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        ViewConfiguration vc = ViewConfiguration.get(this);
        mSlop = vc.getScaledTouchSlop();
        mCardHeight = getResources().getDimensionPixelSize(R.dimen.incoming_call_card_height);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_IGNORE_CHEEK_PRESSES);

        setContentView(R.layout.card_call_incoming);

        InCallPresenter.getInstance().setCardActivity(this);

        final CallList calls = CallList.getInstance();
        mCall = calls.getIncomingCall();

        final CallIdentification identification = mCall.getIdentification();

        // Setup the fields to show the information of the call
        mNameTextView = (TextView) findViewById(R.id.txt_contact_name);
        mContactImage = (ImageView) findViewById(R.id.img_contact);

        ImageView fullscreenUI = (ImageView) findViewById(R.id.fullscreen_ui);
        fullscreenUI.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // start fullscreen ui
                InCallPresenter.getInstance().startIncomingCallUi(InCallPresenter.InCallState.INCALL, false);
                finish();
            }
        });

        ImageView dismissUI = (ImageView) findViewById(R.id.dismiss_ui);
        dismissUI.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // send to background
                InCallPresenter.getInstance().startIncomingCallUi(InCallPresenter.InCallState.INCALL, true);
                // slide out
                finishWithHide();
            }
        });
        GlowPadWrapper glowpad = (GlowPadWrapper) findViewById(R.id.glow_pad_view);
        // Answer or Decline.
        glowpad.setTargetResources(R.array.incoming_call_widget_2way_targets);
        glowpad.setTargetDescriptionsResourceId(
                R.array.incoming_call_widget_2way_target_descriptions);
        glowpad.setDirectionDescriptionsResourceId(
                R.array.incoming_call_widget_2way_direction_descriptions);
        glowpad.reset(false);

        glowpad.setAnswerListener(this);
        glowpad.startPing();

        // Slide in the dialog
        mCard = (LinearLayout) findViewById(R.id.root);
        mCard.setOnTouchListener(new View.OnTouchListener() {

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                int action = event.getAction();
                float yRaw = event.getRawY();

                switch (action) {
                case MotionEvent.ACTION_CANCEL:
                    break;
                case MotionEvent.ACTION_DOWN:
                    mStartY = yRaw;
                    break;
                case MotionEvent.ACTION_UP:
                    // moved down more then 1/3 of the height
                    if (yRaw - mStartY > mCardHeight / 3) {
                        // send in background
                        InCallPresenter.getInstance().startIncomingCallUi(InCallPresenter.InCallState.INCALL, true);
                        // slide out fast
                        mCard.animate().translationY(mCardHeight).setDuration(SLIDE_SWIPE_DURATION_MS)
                                .setInterpolator(new AccelerateInterpolator()).start();
                        mHandler.postDelayed(new Runnable() {
                            public void run() {
                                finish();
                            }
                        }, SLIDE_SWIPE_DURATION_MS);
                    } else {
                        // slide back
                        mCard.animate().translationY(0.0f).setDuration(SLIDE_SWIPE_DURATION_MS)
                            .setInterpolator(new DecelerateInterpolator()).start();
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    float deltaY = event.getRawY() - mStartY;
                    if (deltaY > 0){
                        if(Math.abs(deltaY) > mSlop){
                            mCard.setTranslationY(deltaY);
                        }
                    }
                    break;
                }
                return true;
            }
        });

        // slide in
        mCard.setTranslationY(mCardHeight);
        mCard.animate().translationY(0.0f).setDuration(SLIDE_IN_DURATION_MS)
            .setInterpolator(new DecelerateInterpolator()).start();

        // Lookup contact info - delay until the slide-in animation is done
        // else if can be disturbed and we want it to look smooth
        mHandler.postDelayed(new Runnable() {
            public void run() {
                startContactInfoSearch(identification);
            }
        }, SLIDE_IN_DURATION_MS);
    }

    @Override
    public void onBackPressed() {
        // send to background
        InCallPresenter.getInstance().startIncomingCallUi(InCallPresenter.InCallState.INCALL, true);
        // slide out
        hideCard();
        mHandler.postDelayed(new Runnable() {
            public void run() {
                InCallCardActivity.super.onBackPressed();
            }
        }, SLIDE_OUT_DURATION_MS);
    }

    private void hideCard() {
        mCard.setTranslationY(0);
        mCard.animate().translationY(mCardHeight).setDuration(SLIDE_OUT_DURATION_MS)
                .setInterpolator(new AccelerateInterpolator()).start();
    }

    /**
     * Starts a query for more contact data for the save primary and secondary calls.
     */
    private void startContactInfoSearch(final CallIdentification identification) {
        final ContactInfoCache cache = ContactInfoCache.getInstance(InCallCardActivity.this);

        cache.findInfo(identification, true, new ContactInfoCacheCallback() {
                @Override
                public void onContactInfoComplete(int callId, ContactCacheEntry entry) {
                    mNameTextView.setText(entry.name == null ? entry.number : entry.name);
                    if (entry.personUri != null) {
                        CallerInfoUtils.sendViewNotification(InCallCardActivity.this, entry.personUri);
                    }
                }

                @Override
                public void onImageLoadComplete(int callId, ContactCacheEntry entry) {
                    if (entry.photo != null) {
                        Drawable current = mContactImage.getDrawable();
                        int contactHeight = getResources().getDimensionPixelSize(R.dimen.incoming_call_card_contact_height);
                        Drawable photo = resize(entry.photo, contactHeight);
                        AnimationUtils.startCrossFade(mContactImage, current, photo);
                    }
                }
            });
    }

    @Override
    public void onAnswer() {
        InCallPresenter.getInstance().startIncomingCallUi(InCallPresenter.InCallState.INCALL, false);
        CallCommandClient.getInstance().answerCall(mCall.getCallId());
        finish();
    }

    @Override
    public void onDecline() {
        CallCommandClient.getInstance().rejectCall(mCall, false, null);
        finishWithHide();
    }

    @Override
    public void onText() {
        // should not happen
        finish();
    }

    private BitmapDrawable resize(Drawable image, int dpiSize) {
        float density = getResources().getDisplayMetrics().density;
        int size = Math.round(dpiSize * density);

        Bitmap b = ((BitmapDrawable) image).getBitmap();

        Bitmap bmResult = Bitmap.createBitmap(size, size,
                Bitmap.Config.ARGB_8888);
        Canvas tempCanvas = new Canvas(bmResult);

        Bitmap bitmapResized = Bitmap.createScaledBitmap(b, size,
                size, true);
        tempCanvas.drawBitmap(bitmapResized, 0, 0, null);
        return new BitmapDrawable(getResources(), bmResult);
    }

    // finish with card hide animation
    public void finishWithHide() {
        // dismiss card
        hideCard();
        mHandler.postDelayed(new Runnable() {
            public void run() {
                finish();
            }
        }, SLIDE_OUT_DURATION_MS);
    }
}

