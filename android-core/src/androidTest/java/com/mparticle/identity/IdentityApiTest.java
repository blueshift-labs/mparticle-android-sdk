package com.mparticle.identity;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import com.github.tomakehurst.wiremock.http.Request;
import com.mparticle.MParticle;
import com.mparticle.MParticleTask;
import com.mparticle.internal.ConfigManager;
import com.mparticle.testutils.AndroidUtils.Mutable;
import com.mparticle.testutils.BaseCleanStartedEachTest;
import com.mparticle.testutils.MPLatch;
import com.mparticle.testutils.Server;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNotSame;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;
import static junit.framework.Assert.fail;

public final class IdentityApiTest extends BaseCleanStartedEachTest {
    ConfigManager mConfigManager;
    Handler handler;
    long mpid1, mpid2, mpid3;

    @Before
    public void before() {
        mConfigManager = MParticle.getInstance().Internal().getConfigManager();
        handler = new Handler();
        mpid1 = ran.nextLong();
        mpid2 = ran.nextLong();
        mpid3 = ran.nextLong();
    }

    /**
     * test that when we receive a new MParticleUser from and IdentityApi server call, the correct
     * MParticleUser object is passed to all the possible callbacks
     *  - IdentityStateListener
     *  - MParticleTask<IdentityApiResult>
     *  - MParticle.getInstance().Identity().getCurrentUser()
     */
    @Test
    public void testUserChangeCallbackAccuracy() throws JSONException, InterruptedException {
        final Map<MParticle.IdentityType, String> identities = new HashMap<MParticle.IdentityType, String>();
        identities.put(MParticle.IdentityType.Facebook, "facebooker.me");
        identities.put(MParticle.IdentityType.Email, "tester@mparticle.gov");
        identities.put(MParticle.IdentityType.Google, "hello@googlemail.com");
        final Map<MParticle.IdentityType, String> identities2 = new HashMap<MParticle.IdentityType, String>();
        identities2.put(MParticle.IdentityType.CustomerId, "12345");
        identities2.put(MParticle.IdentityType.Microsoft, "microsoftUser");
        final Map<String, Object> userAttributes = new HashMap<String, Object>();
        userAttributes.put("field1", new JSONObject("{jsonField1:\"value\", json2:3}"));
        userAttributes.put("number2", "HelloWorld");
        userAttributes.put("third", 123);

        final boolean isLoggedIn = ran.nextBoolean();

        mServer.addConditionalLoginResponse(mStartingMpid, mpid1, isLoggedIn);

        final CountDownLatch latch = new MPLatch(2);

        MParticle.getInstance().Identity().addIdentityStateListener(new IdentityStateListener() {
            @Override
            public void onUserIdentified(MParticleUser user) {
                if (user.getId() == mpid1) {
                    try {
                        com.mparticle.internal.AccessUtils.awaitMessageHandler();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    assertMParticleUserEquals(user, mpid1, identities, null, isLoggedIn);
                    latch.countDown();
                }
            }
        });

        IdentityApiRequest request = IdentityApiRequest.withEmptyUser().userIdentities(identities).build();
        MParticleTask<IdentityApiResult> result = MParticle.getInstance().Identity().login(request);

        //test that change actually took place
        result.addSuccessListener(new TaskSuccessListener() {
            @Override
            public void onSuccess(IdentityApiResult identityApiResult) {
                assertMParticleUserEquals(identityApiResult.getUser(), mpid1, identities, null, isLoggedIn);
                latch.countDown();
            }
        });
        latch.await();
    }


    /**
     * happy case, tests that IdentityChangedListener works when added, and stays there
     *
     * @throws Exception
     */
    @Test
    public void testIdentityChangedListenerAdd() throws Exception {
        mServer
                .addConditionalIdentityResponse(mStartingMpid, mpid1, false)
                .addConditionalIdentityResponse(mpid1, mpid2, true);

        final Mutable<Boolean> user1Called = new Mutable<Boolean>(false);
        final Mutable<Boolean> user2Called = new Mutable<Boolean>(false);
        final Mutable<Boolean> user3Called = new Mutable<Boolean>(false);
        final CountDownLatch latch = new MPLatch(3);

        MParticle.getInstance().Identity().addIdentityStateListener(new IdentityStateListener() {
            @Override
            public void onUserIdentified(final MParticleUser user) {
                if (user != null && user.getId() == mpid1) {
                    user1Called.value = true;
                    latch.countDown();
                }
                if (user1Called.value && user.getId() == mpid2) {
                    user2Called.value = true;
                    latch.countDown();
                }
            }
        });

        IdentityApiRequest request = IdentityApiRequest.withEmptyUser().build();
        MParticleTask<IdentityApiResult> result = MParticle.getInstance().Identity().identify(request);

        //test that change actually took place
        result.addSuccessListener(new TaskSuccessListener() {
            @Override
            public void onSuccess(IdentityApiResult identityApiResult) {
                assertEquals(identityApiResult.getUser().getId(), mpid1);
            }
        });

        com.mparticle.internal.AccessUtils.awaitUploadHandler();

        request = IdentityApiRequest.withEmptyUser().build();
        result = MParticle.getInstance().Identity().identify(request);

        result.addSuccessListener(new TaskSuccessListener() {
            @Override
            public void onSuccess(IdentityApiResult identityApiResult) {
                assertEquals(identityApiResult.getUser().getId(), mpid2);
                assertEquals(identityApiResult.getUser().getId(), MParticle.getInstance().Identity().getCurrentUser().getId());
                latch.countDown();
                user3Called.value = true;
            }
        });

        latch.await();
        assertTrue(user1Called.value);
        assertTrue(user2Called.value);
    }

    @Test
    public void testAddMultipleIdentityStateListeners() throws Exception {
        mServer.addConditionalIdentityResponse(mStartingMpid, mpid1);

        final CountDownLatch latch = new MPLatch(6);

        MParticle.getInstance().Identity().addIdentityStateListener(new IdentityStateListener() {
            @Override
            public void onUserIdentified(final MParticleUser user) {
                latch.countDown();
            }
        });

        MParticle.getInstance().Identity().addIdentityStateListener(new IdentityStateListener() {
            @Override
            public void onUserIdentified(final MParticleUser user) {
                latch.countDown();
            }
        });

        MParticle.getInstance().Identity().addIdentityStateListener(new IdentityStateListener() {
            @Override
            public void onUserIdentified(MParticleUser user) {
                latch.countDown();
            }
        });

        IdentityApiRequest request = IdentityApiRequest.withUser(MParticle.getInstance().Identity().getUser(mpid1)).build();
        MParticleTask<IdentityApiResult> result = MParticle.getInstance().Identity().identify(request);

        result.addSuccessListener(new TaskSuccessListener() {
            @Override
            public void onSuccess(IdentityApiResult identityApiResult) {
                latch.countDown();
            }
        }).addSuccessListener(new TaskSuccessListener() {
            @Override
            public void onSuccess(IdentityApiResult result) {
                latch.countDown();
            }
        }).addSuccessListener(new TaskSuccessListener() {
            @Override
            public void onSuccess(IdentityApiResult result) {
                latch.countDown();
            }
        });

      latch.await();
    }

    @Test
    public void testRemoveIdentityStateListeners() throws Exception {
        mServer.addConditionalIdentityResponse(mStartingMpid, mpid1)
                .addConditionalIdentityResponse(mpid1, mpid2);

        final CountDownLatch mpid1Latch = new MPLatch(1);
        final CountDownLatch mpid2Latch = new MPLatch(1);

        IdentityStateListener keptIdStateListener = new IdentityStateListener() {
            @Override
            public void onUserIdentified(final MParticleUser user) {
                if (user.getId() == mpid1) {
                    mpid1Latch.countDown();
                }
                if (user.getId() == mpid2) {
                    mpid2Latch.countDown();
                }
            }
        };

        IdentityStateListener removeIdStateListener1 = new IdentityStateListener() {
            @Override
            public void onUserIdentified(final MParticleUser user) {
                if (user.getId() != mpid1) {
                    fail("IdentityStateListener failed to be removed");
                }
            }
        };
        IdentityStateListener removeIdStateListener2 = new IdentityStateListener() {
            @Override
            public void onUserIdentified(final MParticleUser user) {
                if (user.getId() != mpid1) {
                    fail("IdentityStateListener failed to be removed");
                }            }
        };
        IdentityStateListener removeIdStateListener3 = new IdentityStateListener() {
            @Override
            public void onUserIdentified(final MParticleUser user) {
                if (user.getId() != mpid1) {
                    fail("IdentityStateListener failed to be removed");
                }
            }
        };
        MParticle.getInstance().Identity().addIdentityStateListener(keptIdStateListener);
        MParticle.getInstance().Identity().addIdentityStateListener(removeIdStateListener1);
        MParticle.getInstance().Identity().addIdentityStateListener(removeIdStateListener2);
        MParticle.getInstance().Identity().addIdentityStateListener(removeIdStateListener3);

        MParticle.getInstance().Identity().identify(IdentityApiRequest.withEmptyUser().build());

        mpid1Latch.await();

        MParticle.getInstance().Identity().removeIdentityStateListener(removeIdStateListener1);
        MParticle.getInstance().Identity().removeIdentityStateListener(removeIdStateListener2);
        MParticle.getInstance().Identity().removeIdentityStateListener(removeIdStateListener3);

        MParticle.getInstance().Identity().identify(IdentityApiRequest.withEmptyUser().build());

        mpid2Latch.await();
    }

    /**
     * Make sure that the {@link IdentityStateListener} callbacks are occuring on the Main Thread.
     * This is important so that the KitManagerImpl, which will only instantiate kits on the MainThread,
     * will instantiate kits synchronously
     * @throws InterruptedException
     */
    @Test
    public void testIdentityStateListenerThread() throws InterruptedException {
        final Mutable<Boolean> called = new Mutable<Boolean>(false);
        final CountDownLatch latch = new MPLatch(1);

        HandlerThread backgroundThread = new HandlerThread(mRandomUtils.getAlphaNumericString(8));
        backgroundThread.start();
        new Handler(backgroundThread.getLooper()).post(new Runnable() {
            @Override
            public void run() {
                MParticle.getInstance().Identity().addIdentityStateListener(new IdentityStateListener() {
                    @Override
                    public void onUserIdentified(MParticleUser user) {
                        assertEquals(Looper.getMainLooper(), Looper.myLooper());
                        assertEquals(user.getId(), MParticle.getInstance().Identity().getCurrentUser().getId());
                        called.value = true;
                        latch.countDown();
                    }
                });
                MParticle.getInstance().Internal().getConfigManager().setMpid(mpid1, ran.nextBoolean());
            }
        });
        latch.await();
        assertTrue(called.value);
    }


    @Test
    public void testCallbacksVsCurrentUser() throws InterruptedException {
        mServer.addConditionalLoginResponse(mStartingMpid, mpid1);

        CountDownLatch latch = new MPLatch(2);
        final Mutable<Boolean> called1 = new Mutable<Boolean>(false);
        final Mutable<Boolean> called2 = new Mutable<Boolean>(false);

        MParticle.getInstance().Identity().addIdentityStateListener(new IdentityStateListener() {
            @Override
            public void onUserIdentified(MParticleUser user) {
                assertEquals(mpid1, MParticle.getInstance().Identity().getCurrentUser().getId());
                assertEquals(mpid1, user.getId());
                called1.value = true;
            }
        });

        MParticle.getInstance().Identity().login()
                .addSuccessListener(new TaskSuccessListener() {
                    @Override
                    public void onSuccess(IdentityApiResult result) {
                        assertEquals(mpid1, MParticle.getInstance().Identity().getCurrentUser().getId());
                        assertEquals(mpid1, result.getUser().getId());
                        called2.value = true;
                    }
                });
        latch.await();
        assertTrue(called1.value);
        assertTrue(called2.value);
    }


    private void assertMParticleUserEquals(MParticleUser dto1, Long mpid, Map<MParticle.IdentityType, String> identityTypes, Map<String, Object> userAttributes, boolean isLoggedIn) {
        assertTrue(dto1.getId() == mpid);
        if (userAttributes != null) {
            if (dto1.getUserAttributes() != null) {
                assertEquals(dto1.getUserAttributes().size(), userAttributes.size());
                for (Map.Entry<String, Object> entry : dto1.getUserAttributes().entrySet()) {
                    if (entry.getValue() == null) {
                        assertNull(userAttributes.get(entry.getKey()));
                    } else {
                        assertEquals(entry.getValue().toString(), userAttributes.get(entry.getKey()).toString());
                    }
                }
            }
        } else {
            assertEquals(dto1.getUserAttributes().size(), 0);
        }
        assertEquals(dto1.getUserIdentities().size(), identityTypes.size());
        for (Map.Entry<MParticle.IdentityType, String> entry : dto1.getUserIdentities().entrySet()) {
            assertEquals(entry.getValue(), identityTypes.get(entry.getKey()));
        }
        assertEquals(isLoggedIn, dto1.isLoggedIn());
    }

    @Test
    public void testGetUser() throws Exception {
        IdentityApi identity = MParticle.getInstance().Identity();
        assertNotNull(identity.getCurrentUser());
        MParticle.getInstance().Internal().getConfigManager().setMpid(mpid1, true);
        assertEquals(identity.getCurrentUser().getId(), mpid1);
        Map<String, Object> mpid1UserAttributes = new HashMap<String, Object>(mRandomUtils.getRandomAttributes(10));
        Map<MParticle.IdentityType, String> mpid1UserIdentites = mRandomUtils.getRandomUserIdentities();
        identity.getCurrentUser().setUserAttributes(mpid1UserAttributes);
        AccessUtils.setUserIdentities(mpid1UserIdentites, identity.getCurrentUser().getId());

        MParticle.getInstance().Internal().getConfigManager().setMpid(mpid2, false);
        Map<String, Object> mpid2UserAttributes = new HashMap<String, Object>(mRandomUtils.getRandomAttributes(10));
        Map<MParticle.IdentityType, String> mpid2UserIdentites = mRandomUtils.getRandomUserIdentities();
        identity.getCurrentUser().setUserAttributes(mpid2UserAttributes);
        AccessUtils.setUserIdentities(mpid2UserIdentites, identity.getCurrentUser().getId());


        MParticle.getInstance().Internal().getConfigManager().setMpid(mpid3, true);
        Map<String, Object> mpid3UserAttributes = new HashMap<String, Object>(mRandomUtils.getRandomAttributes(10));
        Map<MParticle.IdentityType, String> mpid3UserIdentities = mRandomUtils.getRandomUserIdentities();
        identity.getCurrentUser().setUserAttributes(mpid3UserAttributes);

        AccessUtils.setUserIdentities(mpid3UserIdentities, identity.getCurrentUser().getId());

        mpid1UserAttributes.remove(null);
        mpid2UserAttributes.remove(null);
        mpid3UserAttributes.remove(null);

        com.mparticle.internal.AccessUtils.awaitMessageHandler();

        //should return null for mpid = 0
        assertNull(identity.getUser(0L));

        //should return an MParticleUser with the correct mpid, userIdentities, and userAttributes for
        //previously seen users
        assertMParticleUserEquals(identity.getUser(mpid1), mpid1, mpid1UserIdentites, mpid1UserAttributes, true);
        assertMParticleUserEquals(identity.getUser(mpid2), mpid2, mpid2UserIdentites, mpid2UserAttributes, false);
        assertMParticleUserEquals(identity.getUser(mpid3), mpid3, mpid3UserIdentities, mpid3UserAttributes, true);

        //should return null for unseen mpid's
        assertNull(identity.getUser(mRandomUtils.randomLong(Long.MIN_VALUE, Long.MAX_VALUE)));
        assertNull(identity.getUser(mRandomUtils.randomLong(Long.MIN_VALUE, Long.MAX_VALUE)));
        assertNull(identity.getUser(mRandomUtils.randomLong(Long.MIN_VALUE, Long.MAX_VALUE)));
    }

    /**
     * this simulates the scenerio in which you make a modify() call, but the current MParticleUser
     * changes between the time you build the request and the time you make the call
     */
    @Test
    public void testModifyConcurrentCalls() throws Exception {
        assertEquals(mStartingMpid, MParticle.getInstance().Identity().getCurrentUser().getId(), 0);

        Map<MParticle.IdentityType, String> userIdentities = mRandomUtils.getRandomUserIdentities();
        for (MParticle.IdentityType identity: userIdentities.keySet()) {
            AccessUtils.setUserIdentity(userIdentities.get(identity), identity, mStartingMpid);
        }
        IdentityApiRequest request = IdentityApiRequest.withUser(MParticle.getInstance().Identity().getCurrentUser()).customerId(mRandomUtils.getAlphaNumericString(24)).build();

        MParticle.getInstance().Identity().modify(request);
        //change the mpid;
        //behind the scenes, this call will take place before the (above) modify request goes out, since
        //the modify request will be passed to a background thread before it is executed
        MParticle.getInstance().Internal().getConfigManager().setMpid(mpid2, ran.nextBoolean());

        final CountDownLatch latch = new MPLatch(1);
        final Mutable<Boolean> received = new Mutable<Boolean>(false);
        mServer.waitForVerify(urlPathMatching(String.format("/v([0-9]*)/%s/modify", mStartingMpid)), new Server.JSONMatch() {
            @Override
            public boolean isMatch(JSONObject jsonObject) {
                if (jsonObject.has("identity_changes")) {
                    try {
                        return jsonObject.getJSONArray("identity_changes").length() == 1;
                    } catch (JSONException jse) {
                        jse.toString();
                    }
                }
                return false;
            }
        }, new Server.RequestReceivedCallback() {
            @Override
            public void onRequestReceived(Request request) {
                received.value = true;
                latch.countDown();
            }
        });
        latch.await();
        assertTrue(received.value);
    }

    @Test
    public void testGetUsersApi() {
        //test that by default there is only the starting user
        assertEquals(MParticle.getInstance().Identity().getUsers().size(), 1);
        assertEquals((Long)MParticle.getInstance().Identity().getUsers().get(0).getId(), mStartingMpid);

        //add 5 Users
        List<Long> mpids = new ArrayList<Long>();
        mpids.add(mStartingMpid);
        for (int i = 0; i < 5; i++) {
            mpids.add(ran.nextLong());
        }

        for (Long mpid: mpids) {
            MParticle.getInstance().Internal().getConfigManager().setMpid(mpid, this.ran.nextBoolean());
        }

        //test that there are now 6 users present in the getUsers() endpoint
        assertEquals(MParticle.getInstance().Identity().getUsers().size(), mpids.size());

        //test that they are the same users we added before
        for (MParticleUser mParticleUser: MParticle.getInstance().Identity().getUsers()) {
            assertTrue(mpids.contains(mParticleUser.getId()));
        }

        //remove 2 users
        for (int i = 0; i < 2; i++) {
            MParticle.getInstance().Internal().getConfigManager().deleteUserStorage(mpids.remove(i));
        }

        //test that there are now 4 remaining users
        assertEquals(MParticle.getInstance().Identity().getUsers().size(), 4);

        //test that they are the correct users
        for (MParticleUser mParticleUser: MParticle.getInstance().Identity().getUsers()) {
            assertTrue(mpids.contains(mParticleUser.getId()));
        }
    }

    /**
     * make sure that there is no way for an MParticleUser with MPID == 0 to be returned from the
     * IdentityAPI
     */
    @Test
    public void testNoZeroMpidUser() {
        assertNull(MParticle.getInstance().Identity().getUser(0L));
        for (MParticleUser user: MParticle.getInstance().Identity().getUsers()) {
            assertNotSame(0, user.getId());
        }
        MParticle.getInstance().Internal().getConfigManager().setMpid(0L, ran.nextBoolean());
        assertNull(MParticle.getInstance().Identity().getUser(0L));
        for (MParticleUser user: MParticle.getInstance().Identity().getUsers()) {
            assertNotSame(0, user.getId());
        }
    }
}

