package com.gmail.altakey.joanne.service;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.widget.Toast;

import com.gmail.altakey.joanne.Attachable;
import com.gmail.altakey.joanne.ConnectivityPolicy;
import com.gmail.altakey.joanne.Joanne;
import com.gmail.altakey.joanne.R;
import com.gmail.altakey.joanne.activity.MainActivity;
import com.gmail.altakey.joanne.util.UserRelation;
import com.gmail.altakey.joanne.view.Radio;
import com.gmail.altakey.joanne.view.RadioProfile;
import com.gmail.altakey.joanne.view.TweetDisplayBuilder;

import java.util.Date;

import twitter4j.DirectMessage;
import twitter4j.StallWarning;
import twitter4j.Status;
import twitter4j.StatusDeletionNotice;
import twitter4j.TwitterException;
import twitter4j.TwitterStream;
import twitter4j.TwitterStreamFactory;
import twitter4j.User;
import twitter4j.UserList;
import twitter4j.UserStreamListener;
import twitter4j.auth.AccessToken;
import twitter4j.conf.ConfigurationBuilder;

public class StreamService extends Service {
    private static final String TAG = "TBS";

    private static final String ACTION_START = "ACTION_START";
    private static final String ACTION_QUIT = "ACTION_QUIT";
    public static final String ACTION_STATE_CHANGED = "ACTION_STATE_CHANGED";

    private static final String EXTRA_TOKEN = AuthService.EXTRA_TOKEN;
    private static final String TWITTER_URL = "https://twitter.com/";

    public static boolean sActive = false;
    private static Handler sHandler = new Handler();
    private Attachable mControlMessageReceiver = new ControlMessageReceiver();

    private TwitterStream mStream;
    private RadioProfile mProfile;
    private String mCurrentStatus;

    private static final String STATUS_READY = "ready";
    private static final String STATUS_FLAKY = "flaky";

    private final IBinder mBinder = new Binder() {
        StreamService getService() {
            return StreamService.this;
        }
    };

    public static final int SERVICE_ID = 1;
    private final NotificationCompat.Builder mNotificationBuilder = new NotificationCompat.Builder(this);

    public static Intent call(final AccessToken token) {
        final Intent i = new Intent(Joanne.getInstance(), StreamService.class);
        i.setAction(ACTION_START);
        i.putExtra(EXTRA_TOKEN, token);
        return i;
    }

    public static Intent quit() {
        final Intent i = new Intent(Joanne.getInstance(), StreamService.class);
        i.setAction(ACTION_QUIT);
        return i;
    }

    private String getServiceStatus() {
        return String.format("%s: %s", getString(R.string.app_name), mCurrentStatus);
    }


    @Override
    public void onCreate() {
        mCurrentStatus = STATUS_READY;

        final Intent quitIntent = new Intent(this, MainActivity.class);
        quitIntent.setAction(MainActivity.ACTION_QUIT);

        final Intent mainIntent = new Intent(this, MainActivity.class);
        mainIntent.setAction(Intent.ACTION_MAIN);

        final Intent viewIntent = new Intent(Intent.ACTION_VIEW);
        viewIntent.setData(Uri.parse(TWITTER_URL));

        final String serviceStatus = getServiceStatus();
        startForeground(SERVICE_ID, mNotificationBuilder
                .setSmallIcon(R.drawable.ic_launcher)
                .setTicker(serviceStatus)
                .setContentTitle(serviceStatus)
                .setContentIntent(PendingIntent.getActivity(this, 0, viewIntent, 0))
                .addAction(android.R.drawable.ic_menu_preferences, "Configure", PendingIntent.getActivity(this, 0, mainIntent, 0))
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Quit", PendingIntent.getActivity(this, 0, quitIntent, 0))
                .build());

        sActive = true;
        LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(ACTION_STATE_CHANGED));
        mControlMessageReceiver.attachTo(this);
    }

    @Override
    public void onDestroy() {
        mControlMessageReceiver.detachFrom(this);
        sActive = false;
    }

    @Override
    public IBinder onBind(final Intent intent) {
        return mBinder;
    }

    @Override
    public int onStartCommand(final Intent intent, final int flags, final int startId) {
        final String action = (intent != null) ? intent.getAction() : null;
        if (ACTION_QUIT.equals(action)) {
            new AsyncTask<Void, Void, Void>() {
                @Override
                protected void onPreExecute() {
                    Toast.makeText(getApplicationContext(), getString(R.string.terminate_in_progress), Toast.LENGTH_SHORT).show();
                }

                @Override
                protected Void doInBackground(Void... voids) {
                    if (mStream != null) {
                        mStream.shutdown();
                    }
                    return null;
                }

                @Override
                protected void onPostExecute(Void aVoid) {
                    mProfile = null;
                    mStream = null;
                    Toast.makeText(getApplicationContext(), getString(R.string.terminating), Toast.LENGTH_SHORT).show();
                    LocalBroadcastManager.getInstance(StreamService.this).sendBroadcast(new Intent(ACTION_STATE_CHANGED));
                    stopSelf(startId);
                }
            }.execute();
        } else {
            if (mStream == null) {
                final AccessToken accessToken = (AccessToken)intent.getSerializableExtra(EXTRA_TOKEN);
                final ConfigurationBuilder builder = new ConfigurationBuilder();
                builder.setOAuthConsumerKey(getString(R.string.consumer_key));
                builder.setOAuthConsumerSecret(getString(R.string.consumer_secret));
                mStream = new TwitterStreamFactory(builder.build()).getInstance(accessToken);
                mProfile = new RadioProfile(getApplicationContext(), mStream);

                mStream.addListener(new StreamListener());
                //final FilterQuery q = new FilterQuery();
                //q.track(new String[] { "#android" });
                //mStream.filter(q);
                mStream.user();

                present(mProfile.ready());
            }
        }
        return START_STICKY;
    }

    private void present(final Iterable<Radio> radios) {
        for (Radio radio: radios) {
            if (radio != null) {
                final PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm.isScreenOn()) {
                    sHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            new TweetDisplayBuilder(getApplicationContext(), radio).build().show();
                        }
                    });
                }

                if (radio.isError()) {
                    mCurrentStatus = STATUS_FLAKY;
                } else {
                    mCurrentStatus = STATUS_READY;
                }

                final NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                nm.notify(SERVICE_ID, mNotificationBuilder
                        .setContentTitle(getServiceStatus())
                        .setContentText(radio.getRawText())
                        .setContentInfo(radio.getScreenName())
                        .setWhen(new Date().getTime())
                        .build());
            }
        }
    }

    private class ControlMessageReceiver extends BroadcastReceiver implements Attachable {
        @Override
        public void attachTo(Context c) {
            final IntentFilter f = new IntentFilter();
            f.addAction(ConnectivityPolicy.ACTION_CONNECT);
            f.addAction(ConnectivityPolicy.ACTION_DISCONNECT);
            LocalBroadcastManager.getInstance(c).registerReceiver(this, f);
        }

        @Override
        public void detachFrom(Context c) {
            LocalBroadcastManager.getInstance(c).unregisterReceiver(this);
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            if (mStream != null) {
                switch (intent.getAction()) {
                    case ConnectivityPolicy.ACTION_DISCONNECT:
                        Log.d(TAG, "pausing connection");
                        new AsyncTask<Void,Void,Void>() {
                            @Override
                            protected Void doInBackground(Void... params) {
                                mStream.shutdown();
                                return null;
                            }
                        }.execute();
                        break;
                    case ConnectivityPolicy.ACTION_CONNECT:
                        Log.d(TAG, "resuming connection");
                        new AsyncTask<Void,Void,Void>() {
                            @Override
                            protected Void doInBackground(Void... params) {
                                mStream.user();
                                return null;
                            }
                        }.execute();
                        present(mProfile.ready());
                        break;
                }
            }
        }
    }

    private class StreamListener implements UserStreamListener {
        @Override
        public void onStatus(final Status status) {
            present(mProfile.status(status));
        }

        @Override
        public void onDeletionNotice(StatusDeletionNotice statusDeletionNotice) {
            present(mProfile.deletion());
        }

        @Override
        public void onTrackLimitationNotice(int i) { }

        @Override
        public void onScrubGeo(long l, long l2) { }

        @Override
        public void onStallWarning(StallWarning stallWarning) { }

        @Override
        public void onException(Exception e) {
            Log.w(TAG, "got exception while tracing up stream", e);
            present(mProfile.error());
        }

        @Override
        public void onDeletionNotice(long l, long l2) { }

        @Override
        public void onFriendList(long[] longs) { }

        @Override
        public void onFavorite(final User source, final User target, Status status) {
            present(mProfile.favorite(source, target));
        }

        @Override
        public void onUnfavorite(User user, User user2, Status status) { }

        @Override
        public void onFollow(final User source, final User target) {
            present(mProfile.follow(source, target));
            try {
                UserRelation.update(StreamService.this, mStream.getOAuthAccessToken());
            } catch (TwitterException e) {
                Log.w(TAG, "cannot update friends list", e);
            }
        }

        @Override
        public void onUnfollow(final User source, final User target) {
        }

        @Override
        public void onDirectMessage(DirectMessage directMessage) { }

        @Override
        public void onUserListMemberAddition(final User addedMember, final User listOwner, UserList userList) {
            present(mProfile.listed(listOwner, addedMember));
        }

        @Override
        public void onUserListMemberDeletion(final User deletedMember, final User listOwner, UserList userList) {
            present(mProfile.unlisted(listOwner, deletedMember));
        }

        @Override
        public void onUserListSubscription(User user, User user2, UserList userList) { }

        @Override
        public void onUserListUnsubscription(User user, User user2, UserList userList) { }

        @Override
        public void onUserListCreation(User user, UserList userList) { }

        @Override
        public void onUserListUpdate(User user, UserList userList) { }

        @Override
        public void onUserListDeletion(User user, UserList userList) { }

        @Override
        public void onUserProfileUpdate(User user) { }

        @Override
        public void onBlock(final User source, final User target) {
            present(mProfile.block(source, target));
        }

        @Override
        public void onUnblock(User user, User user2) { }
    }
}
