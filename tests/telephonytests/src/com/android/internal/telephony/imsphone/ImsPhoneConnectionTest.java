/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.internal.telephony.imsphone;

import static android.telephony.ims.ImsStreamMediaProfile.AUDIO_QUALITY_AMR_WB;
import static android.telephony.ims.ImsStreamMediaProfile.AUDIO_QUALITY_EVS_SWB;

import static com.android.internal.telephony.TelephonyTestUtils.waitForMs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyChar;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.AsyncResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PersistableBundle;
import android.telephony.CarrierConfigManager;
import android.telephony.DisconnectCause;
import android.telephony.PhoneNumberUtils;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.telephony.ims.ImsCallProfile;
import android.telephony.ims.ImsStreamMediaProfile;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.MediumTest;
import androidx.test.filters.SmallTest;

import com.android.ims.ImsCall;
import com.android.ims.ImsException;
import com.android.internal.telephony.Call;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.GsmCdmaCall;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.TelephonyTest;
import com.android.internal.telephony.imsphone.ImsPhone.ImsDialArgs;
import com.android.internal.telephony.metrics.TelephonyMetrics;
import com.android.internal.telephony.metrics.VoiceCallSessionStats;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class ImsPhoneConnectionTest extends TelephonyTest {
    private static final int TIMEOUT_MILLIS = 5000;

    private ImsPhoneConnection mConnectionUT;
    private Bundle mBundle = new Bundle();

    // Mocked classes
    private ImsPhoneCall mForeGroundCall;
    private ImsPhoneCall mBackGroundCall;
    private ImsPhoneCall mRingGroundCall;

    @Before
    public void setUp() throws Exception {
        super.setUp(getClass().getSimpleName());
        mForeGroundCall = mock(ImsPhoneCall.class);
        mBackGroundCall = mock(ImsPhoneCall.class);
        mRingGroundCall = mock(ImsPhoneCall.class);
        mTelephonyManager = mock(TelephonyManager.class);
        mCarrierConfigManager = mock(CarrierConfigManager.class);
        replaceInstance(Handler.class, "mLooper", mImsCT, Looper.myLooper());
        replaceInstance(ImsPhoneCallTracker.class, "mForegroundCall", mImsCT, mForeGroundCall);
        replaceInstance(ImsPhoneCallTracker.class, "mBackgroundCall", mImsCT, mBackGroundCall);
        replaceInstance(ImsPhoneCallTracker.class, "mRingingCall", mImsCT, mRingGroundCall);
        replaceInstance(ImsPhoneCallTracker.class, "mPhone", mImsCT, mImsPhone);

        mImsCallProfile.mCallExtras = mBundle;
        doReturn(ImsPhoneCall.State.IDLE).when(mForeGroundCall).getState();

        // By default, turn off the business composer
        setUserEnabledBusinessComposer(false);
        setCarrierConfigBusinessComposer(false);
    }

    @After
    public void tearDown() throws Exception {
        mBundle = null;
        mConnectionUT = null;
        super.tearDown();
    }

    @Test
    @SmallTest
    public void testNullExtras() {
        mImsCallProfile.mCallExtras = null;
        try {
            mConnectionUT = new ImsPhoneConnection(mImsPhone, mImsCall, mImsCT, mForeGroundCall,
                    false);
        } catch (NullPointerException npe) {
            Assert.fail("Should not get NPE updating extras.");
        }
    }

    @Test
    @SmallTest
    public void testImsIncomingConnectionCorrectness() {
        logd("Testing initial state of MT ImsPhoneConnection");
        mConnectionUT = new ImsPhoneConnection(mImsPhone, mImsCall, mImsCT, mForeGroundCall, false);

        assertEquals(ImsPhoneCall.State.IDLE, mConnectionUT.getState());
        assertEquals(PhoneConstants.PRESENTATION_UNKNOWN, mConnectionUT.getNumberPresentation());
        assertEquals(PhoneConstants.PRESENTATION_UNKNOWN, mConnectionUT.getCnapNamePresentation());
        assertEquals(Connection.PostDialState.NOT_STARTED, mConnectionUT.getPostDialState());
        assertEquals(0, mConnectionUT.getDisconnectTime());
        assertEquals(0, mConnectionUT.getHoldDurationMillis());
        assertNull(mConnectionUT.getOrigDialString());
        assertFalse(mConnectionUT.isMultiparty());
        assertFalse(mConnectionUT.isConferenceHost());
        assertEquals(android.telecom.Connection.VERIFICATION_STATUS_PASSED,
                mConnectionUT.getNumberVerificationStatus());
        verify(mForeGroundCall, times(1)).attach((Connection) any(),
                eq(ImsPhoneCall.State.INCOMING));

        logd("Testing initial state of MO ImsPhoneConnection");
        mConnectionUT = new ImsPhoneConnection(mImsPhone, String.format("+1 (700).555-41NN%c1234",
                PhoneNumberUtils.PAUSE), mImsCT, mForeGroundCall, false, false,
                new ImsDialArgs.Builder().build());
        assertEquals(PhoneConstants.PRESENTATION_ALLOWED, mConnectionUT.getNumberPresentation());
        assertEquals(PhoneConstants.PRESENTATION_ALLOWED, mConnectionUT.getCnapNamePresentation());
        assertEquals("+1 (700).555-41NN,1234", mConnectionUT.getOrigDialString());
        verify(mForeGroundCall, times(1)).attachFake((Connection) any(),
                eq(ImsPhoneCall.State.DIALING));
    }

    @Test
    @SmallTest
    public void testImsUpdateStateForeGround() {
        // MO Foreground Connection dailing -> active
        mConnectionUT = new ImsPhoneConnection(mImsPhone, "+1 (700).555-41NN1234", mImsCT,
                mForeGroundCall, false, false, new ImsDialArgs.Builder().build());
        // initially in dialing state
        doReturn(Call.State.DIALING).when(mForeGroundCall).getState();
        assertTrue(mConnectionUT.update(mImsCall, Call.State.ACTIVE));
        // for Ringing/Dialing upadte postDialState
        assertEquals(Connection.PostDialState.COMPLETE, mConnectionUT.getPostDialState());
        verify(mForeGroundCall, times(1)).update(eq(mConnectionUT), eq(mImsCall),
                eq(Call.State.ACTIVE));
    }

    @Test
    @SmallTest
    public void testUpdateCodec() {
        // MO Foreground Connection dailing -> active
        mConnectionUT = new ImsPhoneConnection(mImsPhone, "+1 (700).555-41NN1234", mImsCT,
                mForeGroundCall, false, false, new ImsDialArgs.Builder().build());
        doReturn(Call.State.ACTIVE).when(mForeGroundCall).getState();
        assertTrue(mConnectionUT.updateMediaCapabilities(mImsCall));
    }

    @Test
    @SmallTest
    public void testImsUpdateStateBackGround() {
        // MT background Connection dialing -> active
        mConnectionUT = new ImsPhoneConnection(mImsPhone, mImsCall, mImsCT, mBackGroundCall, false);
        doReturn(Call.State.HOLDING).when(mBackGroundCall).getState();
        assertFalse(mConnectionUT.update(mImsCall, Call.State.ACTIVE));
        verify(mBackGroundCall, times(1)).detach(eq(mConnectionUT));
        verify(mForeGroundCall, times(1)).attach(eq(mConnectionUT));
        verify(mForeGroundCall, times(1)).update(eq(mConnectionUT), eq(mImsCall),
                eq(Call.State.ACTIVE));
        assertEquals(Connection.PostDialState.NOT_STARTED, mConnectionUT.getPostDialState());
    }

    @Test
    @SmallTest
    public void testImsUpdateStatePendingHold() {
        mConnectionUT = new ImsPhoneConnection(mImsPhone, "+1 (700).555-41NN1234", mImsCT,
                mForeGroundCall, false, false, new ImsDialArgs.Builder().build());
        doReturn(true).when(mImsCall).isPendingHold();
        assertFalse(mConnectionUT.update(mImsCall, Call.State.ACTIVE));
        verify(mForeGroundCall, times(0)).update(eq(mConnectionUT), eq(mImsCall),
                eq(Call.State.ACTIVE));
        assertEquals(Connection.PostDialState.NOT_STARTED, mConnectionUT.getPostDialState());
    }

    @Test
    @SmallTest
    public void testUpdateAddressDisplay() {
        mConnectionUT = new ImsPhoneConnection(mImsPhone, mImsCall, mImsCT, mForeGroundCall, false);
        assertEquals(PhoneConstants.PRESENTATION_UNKNOWN, mConnectionUT.getNumberPresentation());
        assertEquals(PhoneConstants.PRESENTATION_UNKNOWN, mConnectionUT.getCnapNamePresentation());
        mImsCallProfile.setCallExtraInt(ImsCallProfile.EXTRA_CNAP,
                ImsCallProfile.OIR_PRESENTATION_PAYPHONE);
        mImsCallProfile.setCallExtraInt(ImsCallProfile.EXTRA_OIR,
                ImsCallProfile.OIR_PRESENTATION_NOT_RESTRICTED);

        mConnectionUT.updateAddressDisplay(mImsCall);
        assertEquals(ImsCallProfile.OIRToPresentation(ImsCallProfile.OIR_PRESENTATION_PAYPHONE),
                mConnectionUT.getCnapNamePresentation());
        assertEquals(ImsCallProfile.OIRToPresentation(
                        ImsCallProfile.OIR_PRESENTATION_NOT_RESTRICTED),
                mConnectionUT.getNumberPresentation());
    }

    @Test
    @SmallTest
    public void testConnectionDisconnect() {
        //Mock we have an active connection
        testImsUpdateStateForeGround();
        // tested using System.currentTimeMillis()
        waitForMs(50);
        mConnectionUT.onDisconnect(DisconnectCause.LOCAL);
        assertEquals(DisconnectCause.LOCAL, mConnectionUT.getDisconnectCause());
        assertEquals(GsmCdmaCall.State.DISCONNECTED, mConnectionUT.getState());
        assertTrue(mConnectionUT.getDisconnectTime() <= System.currentTimeMillis());
        assertTrue(mConnectionUT.getDurationMillis() >= 50);
    }

    @Test
    @SmallTest
    public void testPostDialWait() {
        mConnectionUT = new ImsPhoneConnection(mImsPhone, String.format("+1 (700).555-41NN%c1234",
                PhoneNumberUtils.WAIT), mImsCT, mForeGroundCall, false, false,
                new ImsDialArgs.Builder().build());
        doReturn(Call.State.DIALING).when(mForeGroundCall).getState();
        doAnswer(new Answer() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                Message msg = (Message) invocation.getArguments()[1];
                AsyncResult.forMessage(msg);
                msg.sendToTarget();
                return  null;
            }
        }).when(mImsCT).sendDtmf(anyChar(), (Message) any());
        // process post dial string during update
        assertTrue(mConnectionUT.update(mImsCall, Call.State.ACTIVE));
        assertEquals(Connection.PostDialState.WAIT, mConnectionUT.getPostDialState());
        mConnectionUT.proceedAfterWaitChar();
        processAllMessages();
        assertEquals(Connection.PostDialState.COMPLETE, mConnectionUT.getPostDialState());
    }

    @Test
    @MediumTest
    public void testPostDialPause() {
        mConnectionUT = new ImsPhoneConnection(mImsPhone, String.format("+1 (700).555-41NN%c1234",
                PhoneNumberUtils.PAUSE), mImsCT, mForeGroundCall, false, false,
                new ImsDialArgs.Builder().build());
        doReturn(Call.State.DIALING).when(mForeGroundCall).getState();
        doAnswer(new Answer() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                Message msg = (Message) invocation.getArguments()[1];
                AsyncResult.forMessage(msg);
                msg.sendToTarget();
                return null;
            }
        }).when(mImsCT).sendDtmf(anyChar(), (Message) any());

        // process post dial string during update
        assertTrue(mConnectionUT.update(mImsCall, Call.State.ACTIVE));
        assertEquals(Connection.PostDialState.STARTED, mConnectionUT.getPostDialState());
        moveTimeForward(ImsPhoneConnection.PAUSE_DELAY_MILLIS);
        processAllMessages();
        assertEquals(Connection.PostDialState.COMPLETE, mConnectionUT.getPostDialState());
    }

    @Test
    @SmallTest
    public void testSetWifiDeprecated() {
        mConnectionUT = new ImsPhoneConnection(mImsPhone, mImsCall, mImsCT, mForeGroundCall, false);
        assertFalse(mConnectionUT.isWifi());
        // ImsCall.getRadioTechnology is tested elsewhere
        doReturn(TelephonyManager.NETWORK_TYPE_IWLAN).when(mImsCall).getNetworkType();
        mBundle.putString(ImsCallProfile.EXTRA_CALL_RAT_TYPE,
                ServiceState.RIL_RADIO_TECHNOLOGY_IWLAN + "");
        assertTrue(mConnectionUT.update(mImsCall, Call.State.ACTIVE));
        assertTrue(mConnectionUT.isWifi());
    }

    @Test
    @SmallTest
    public void testSetWifi() {
        mConnectionUT = new ImsPhoneConnection(mImsPhone, mImsCall, mImsCT, mForeGroundCall, false);
        assertFalse(mConnectionUT.isWifi());
        // ImsCall.getRadioTechnology is tested elsewhere
        doReturn(TelephonyManager.NETWORK_TYPE_IWLAN).when(mImsCall).getNetworkType();
        mBundle.putString(ImsCallProfile.EXTRA_CALL_NETWORK_TYPE,
                TelephonyManager.NETWORK_TYPE_IWLAN + "");
        assertTrue(mConnectionUT.update(mImsCall, Call.State.ACTIVE));
        assertTrue(mConnectionUT.isWifi());
    }

    @Test
    @SmallTest
    public void testSetWifi2() {
        mConnectionUT = new ImsPhoneConnection(mImsPhone, mImsCall, mImsCT, mForeGroundCall, false);
        assertFalse(mConnectionUT.isWifi());
        // ImsCall.getRadioTechnology is tested elsewhere
        doReturn(TelephonyManager.NETWORK_TYPE_IWLAN).when(mImsCall).getNetworkType();
        // Tests to make sure that the EXTRA_CALL_RAT_TYPE_ALT string is set correctly for newer
        // devices.
        mBundle.putString(ImsCallProfile.EXTRA_CALL_RAT_TYPE_ALT,
                ServiceState.RIL_RADIO_TECHNOLOGY_IWLAN + "");
        assertTrue(mConnectionUT.update(mImsCall, Call.State.ACTIVE));
        assertTrue(mConnectionUT.isWifi());
    }

    @Test
    @SmallTest
    public void testSetLTEDeprecated() {
        mConnectionUT = new ImsPhoneConnection(mImsPhone, mImsCall, mImsCT, mForeGroundCall, false);
        assertNotEquals(mConnectionUT.getCallRadioTech(), ServiceState.RIL_RADIO_TECHNOLOGY_LTE);
        // ImsCall.getRadioTechnology is tested elsewhere
        doReturn(TelephonyManager.NETWORK_TYPE_LTE).when(mImsCall).getNetworkType();
        mBundle.putString(ImsCallProfile.EXTRA_CALL_RAT_TYPE,
                ServiceState.RIL_RADIO_TECHNOLOGY_LTE + "");
        assertTrue(mConnectionUT.update(mImsCall, Call.State.ACTIVE));
        assertEquals(mConnectionUT.getCallRadioTech(), ServiceState.RIL_RADIO_TECHNOLOGY_LTE);
    }

    @Test
    @SmallTest
    public void testSetLTE() {
        mConnectionUT = new ImsPhoneConnection(mImsPhone, mImsCall, mImsCT, mForeGroundCall, false);
        assertNotEquals(mConnectionUT.getCallRadioTech(), ServiceState.RIL_RADIO_TECHNOLOGY_LTE);
        // ImsCall.getRadioTechnology is tested elsewhere
        doReturn(TelephonyManager.NETWORK_TYPE_LTE).when(mImsCall).getNetworkType();
        mBundle.putString(ImsCallProfile.EXTRA_CALL_NETWORK_TYPE,
                TelephonyManager.NETWORK_TYPE_LTE + "");
        assertTrue(mConnectionUT.update(mImsCall, Call.State.ACTIVE));
        assertEquals(mConnectionUT.getCallRadioTech(), ServiceState.RIL_RADIO_TECHNOLOGY_LTE);
    }

    @Test
    @SmallTest
    public void testSetLTE2() {
        mConnectionUT = new ImsPhoneConnection(mImsPhone, mImsCall, mImsCT, mForeGroundCall, false);
        assertNotEquals(mConnectionUT.getCallRadioTech(), ServiceState.RIL_RADIO_TECHNOLOGY_LTE);
        // ImsCall.getRadioTechnology is tested elsewhere
        doReturn(TelephonyManager.NETWORK_TYPE_LTE).when(mImsCall).getNetworkType();
        // Tests to make sure that the EXTRA_CALL_RAT_TYPE_ALT string is set correctly for newer
        // devices.
        mBundle.putString(ImsCallProfile.EXTRA_CALL_RAT_TYPE_ALT,
                ServiceState.RIL_RADIO_TECHNOLOGY_LTE + "");
        assertTrue(mConnectionUT.update(mImsCall, Call.State.ACTIVE));
        assertEquals(mConnectionUT.getCallRadioTech(), ServiceState.RIL_RADIO_TECHNOLOGY_LTE);
    }

    /**
     * Test updates to address for incoming calls.
     */
    @Test
    @SmallTest
    public void testAddressUpdate() {
        String[] testAddressMappingSet[] = {
                /* {"inputAddress", "updateAddress", "ExpectResult"} */
                {"12345", "12345", "12345"},
                {"12345", "67890", "67890"},
                {"12345*00000", "12345", "12345*00000"},
                {"12345*00000", "67890", "67890"},
                {"12345*00000", "12345*00000", "12345*00000"},
                {"12345;11111*00000", "12345", "12345"},
                {"12345*00000;11111", "12345", "12345*00000"},
                {"18412345*00000", "18412345", "18412345*00000"},
                {"+8112345*00000", "+8112345", "+8112345*00000"},
                {"12345*00000", "12346", "12346"}};
        for (String[] testAddress : testAddressMappingSet) {
            mConnectionUT = new ImsPhoneConnection(mImsPhone, testAddress[0], mImsCT,
                    mForeGroundCall, false, false, new ImsDialArgs.Builder().build());
            mConnectionUT.setIsIncoming(true);
            mImsCallProfile.setCallExtra(ImsCallProfile.EXTRA_OI, testAddress[1]);
            mConnectionUT.updateAddressDisplay(mImsCall);
            assertEquals(testAddress[2], mConnectionUT.getAddress());
        }
    }

    /**
     * Ensure updates to the address for outgoing calls are ignored.
     */
    @Test
    @SmallTest
    public void testSetAddressOnOutgoing() {
        String inputAddress = "12345";
        String updateAddress = "6789";

        mConnectionUT = new ImsPhoneConnection(mImsPhone, inputAddress, mImsCT, mForeGroundCall,
                false, false, new ImsDialArgs.Builder().build());
        mConnectionUT.setIsIncoming(false);
        mImsCallProfile.setCallExtra(ImsCallProfile.EXTRA_OI, updateAddress);
        mConnectionUT.updateAddressDisplay(mImsCall);
        assertEquals(inputAddress, mConnectionUT.getAddress());
    }

    @Test
    @SmallTest
    public void testConvertVerificationStatus() {
        assertEquals(android.telecom.Connection.VERIFICATION_STATUS_FAILED,
                ImsPhoneConnection.toTelecomVerificationStatus(
                        ImsCallProfile.VERIFICATION_STATUS_FAILED));
        assertEquals(android.telecom.Connection.VERIFICATION_STATUS_PASSED,
                ImsPhoneConnection.toTelecomVerificationStatus(
                        ImsCallProfile.VERIFICATION_STATUS_PASSED));
        assertEquals(android.telecom.Connection.VERIFICATION_STATUS_NOT_VERIFIED,
                ImsPhoneConnection.toTelecomVerificationStatus(
                        ImsCallProfile.VERIFICATION_STATUS_NOT_VERIFIED));
        assertEquals(android.telecom.Connection.VERIFICATION_STATUS_NOT_VERIFIED,
                ImsPhoneConnection.toTelecomVerificationStatus(90210));
    }

    /**
     * Assert the helper method
     * {@link ImsPhoneConnection#isBusinessOnlyCallComposerEnabledByUser(Phone)} is Working As
     * Intended.
     */
    @Test
    @SmallTest
    public void testIsBusinessOnlyCallComposerEnabledByUser() {
        mConnectionUT = new ImsPhoneConnection(mImsPhone, mImsCall, mImsCT, mForeGroundCall, false);
        assertFalse(mConnectionUT.isBusinessOnlyCallComposerEnabledByUser(mImsPhone));
        setUserEnabledBusinessComposer(true);
        assertTrue(mConnectionUT.isBusinessOnlyCallComposerEnabledByUser(mImsPhone));
        setUserEnabledBusinessComposer(false);
        assertFalse(mConnectionUT.isBusinessOnlyCallComposerEnabledByUser(mImsPhone));
    }

    /**
     * Assert the helper method
     * {@link ImsPhoneConnection#isBusinessComposerEnabledByConfig(Phone)} is Working As
     * Intended.
     */
    @Test
    @SmallTest
    public void testBusinessComposerEnabledByCarrierConfig() {
        mConnectionUT = new ImsPhoneConnection(mImsPhone, mImsCall, mImsCT, mForeGroundCall, false);
        assertFalse(mConnectionUT.isBusinessComposerEnabledByConfig(mImsPhone));
        setCarrierConfigBusinessComposer(true);
        assertTrue(mConnectionUT.isBusinessComposerEnabledByConfig(mImsPhone));
        setCarrierConfigBusinessComposer(false);
        assertFalse(mConnectionUT.isBusinessComposerEnabledByConfig(mImsPhone));
    }

    /**
     * Verify that the {@link ImsPhoneConnection#getIsBusinessComposerFeatureEnabled()} only
     * returns true when it is enabled by the CarrierConfigManager and user.
     */
    @Test
    @SmallTest
    public void testIncomingImsCallSetsTheBusinessComposerFeatureStatus() {
        mConnectionUT = new ImsPhoneConnection(mImsPhone, mImsCall, mImsCT, mForeGroundCall, false);
        assertFalse(mConnectionUT.getIsBusinessComposerFeatureEnabled());

        setUserEnabledBusinessComposer(true);
        setCarrierConfigBusinessComposer(false);
        mConnectionUT = new ImsPhoneConnection(mImsPhone, mImsCall, mImsCT, mForeGroundCall, false);
        assertFalse(mConnectionUT.getIsBusinessComposerFeatureEnabled());

        setUserEnabledBusinessComposer(false);
        setCarrierConfigBusinessComposer(true);
        mConnectionUT = new ImsPhoneConnection(mImsPhone, mImsCall, mImsCT, mForeGroundCall, false);
        assertFalse(mConnectionUT.getIsBusinessComposerFeatureEnabled());

        setUserEnabledBusinessComposer(true);
        setCarrierConfigBusinessComposer(true);
        mConnectionUT = new ImsPhoneConnection(mImsPhone, mImsCall, mImsCT, mForeGroundCall, false);
        assertTrue(mConnectionUT.getIsBusinessComposerFeatureEnabled());
    }

    /**
     * If the business composer feature is off but ImsCallProfile extras still injected by the lower
     * layer, Telephony should NOT inject the telecom call extras.
     */
    @Test
    @SmallTest
    public void testMaybeInjectBusinessExtrasWithFeatureOff() {
        setUserEnabledBusinessComposer(false);
        setCarrierConfigBusinessComposer(false);
        mConnectionUT = new ImsPhoneConnection(mImsPhone, mImsCall, mImsCT, mForeGroundCall, false);
        assertFalse(mConnectionUT.getIsBusinessComposerFeatureEnabled());
        Bundle businessExtras = getBusinessExtras();
        mConnectionUT.maybeInjectBusinessComposerExtras(businessExtras);
        assertFalse(businessExtras.containsKey(android.telecom.Call.EXTRA_IS_BUSINESS_CALL));
        assertFalse(businessExtras.containsKey(android.telecom.Call.EXTRA_ASSERTED_DISPLAY_NAME));
    }

    /**
     * Verify if the business composer feature is on, telephony is injecting the telecom call extras
     */
    @Test
    @SmallTest
    public void testMaybeInjectBusinessExtrasWithFeatureOn() {
        setUserEnabledBusinessComposer(true);
        setCarrierConfigBusinessComposer(true);
        mConnectionUT = new ImsPhoneConnection(mImsPhone, mImsCall, mImsCT, mForeGroundCall, false);
        assertTrue(mConnectionUT.getIsBusinessComposerFeatureEnabled());
        Bundle businessExtras = getBusinessExtras();
        mConnectionUT.maybeInjectBusinessComposerExtras(businessExtras);
        assertTrue(businessExtras.containsKey(android.telecom.Call.EXTRA_IS_BUSINESS_CALL));
        assertTrue(businessExtras.containsKey(android.telecom.Call.EXTRA_ASSERTED_DISPLAY_NAME));
    }

    @Test
    @SmallTest
    public void testSetRedirectingAddress() {
        mConnectionUT = new ImsPhoneConnection(mImsPhone, mImsCall, mImsCT, mForeGroundCall, false);
        String[] forwardedNumber = new String[]{"11111", "22222", "33333"};
        ArrayList<String> forwardedNumberList =
                new ArrayList<String>(Arrays.asList(forwardedNumber));

        assertEquals(mConnectionUT.getForwardedNumber(), null);
        mBundle.putStringArray(ImsCallProfile.EXTRA_FORWARDED_NUMBER, forwardedNumber);
        assertTrue(mConnectionUT.update(mImsCall, Call.State.ACTIVE));
        assertEquals(forwardedNumberList, mConnectionUT.getForwardedNumber());
    }

    @Test
    @SmallTest
    public void testReportMediaCodecChange() throws InterruptedException, ImsException {
        ImsCall imsCall = mock(ImsCall.class);
        ImsStreamMediaProfile mediaProfile = new ImsStreamMediaProfile();
        ImsCallProfile profile = new ImsCallProfile();
        profile.mMediaProfile = mediaProfile;
        mediaProfile.mAudioQuality = AUDIO_QUALITY_AMR_WB;
        when(imsCall.getLocalCallProfile()).thenReturn(profile);

        // Blech; mocks required which are unrelated to this test
        when(mImsCT.getPhone()).thenReturn(mImsPhone);
        VoiceCallSessionStats stats = mock(VoiceCallSessionStats.class);
        when(mImsPhone.getVoiceCallSessionStats()).thenReturn(stats);

        mConnectionUT = new ImsPhoneConnection(mImsPhone, imsCall, mImsCT, mForeGroundCall, false);
        mConnectionUT.setTelephonyMetrics(mock(TelephonyMetrics.class));
        CountDownLatch latch = new CountDownLatch(1);
        boolean[] receivedCountCallback = new boolean[1];
        mConnectionUT.addListener(new Connection.ListenerBase() {
            @Override
            public void onMediaAttributesChanged() {
                receivedCountCallback[0] = true;
                latch.countDown();
            }
        });

        mConnectionUT.updateMediaCapabilities(imsCall);

        // Make an update to the media caps
        mediaProfile.mAudioQuality = AUDIO_QUALITY_EVS_SWB;
        mConnectionUT.updateMediaCapabilities(imsCall);

        latch.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        assertTrue(receivedCountCallback[0]);
    }

    private void setUserEnabledBusinessComposer(boolean isEnabled) {
        when(mPhone.getContext()).thenReturn(mContext);
        when(mContext.getSystemService(TelephonyManager.class)).thenReturn(mTelephonyManager);
        if (isEnabled) {
            when(mTelephonyManager.getCallComposerStatus()).thenReturn(
                    TelephonyManager.CALL_COMPOSER_STATUS_BUSINESS_ONLY);
        } else {
            when(mTelephonyManager.getCallComposerStatus()).thenReturn(
                    TelephonyManager.CALL_COMPOSER_STATUS_OFF);
        }
    }

    private void setCarrierConfigBusinessComposer(boolean isEnabled) {
        when(mPhone.getContext()).thenReturn(mContext);
        when(mContext.getSystemService(CarrierConfigManager.class)).thenReturn(
                mCarrierConfigManager);
        PersistableBundle b = new PersistableBundle();
        b.putBoolean(CarrierConfigManager.KEY_SUPPORTS_BUSINESS_CALL_COMPOSER_BOOL, isEnabled);
        when(mCarrierConfigManager.getConfigForSubId(mPhone.getSubId())).thenReturn(b);
    }

    private Bundle getBusinessExtras() {
        Bundle businessExtras = new Bundle();
        businessExtras.putBoolean(ImsCallProfile.EXTRA_IS_BUSINESS_CALL, true);
        businessExtras.putString(ImsCallProfile.EXTRA_ASSERTED_DISPLAY_NAME, "Google");
        return businessExtras;
    }
}
