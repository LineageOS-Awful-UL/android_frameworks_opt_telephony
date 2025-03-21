/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.internal.telephony.euicc;

import static android.telephony.euicc.EuiccCardManager.RESET_OPTION_DELETE_OPERATIONAL_PROFILES;
import static android.telephony.euicc.EuiccManager.EUICC_OTA_STATUS_UNAVAILABLE;
import static android.telephony.euicc.EuiccManager.SWITCH_WITHOUT_PORT_INDEX_EXCEPTION_ON_DISABLE;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.annotation.Nullable;
import android.app.PendingIntent;
import android.app.admin.flags.Flags;
import android.compat.testing.PlatformCompatChangeRule;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.UserManager;
import android.platform.test.flag.junit.SetFlagsRule;
import android.provider.Settings;
import android.service.euicc.DownloadSubscriptionResult;
import android.service.euicc.EuiccService;
import android.service.euicc.GetDefaultDownloadableSubscriptionListResult;
import android.service.euicc.GetDownloadableSubscriptionMetadataResult;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.UiccAccessRule;
import android.telephony.UiccCardInfo;
import android.telephony.UiccPortInfo;
import android.telephony.euicc.DownloadableSubscription;
import android.telephony.euicc.EuiccInfo;
import android.telephony.euicc.EuiccManager;

import androidx.test.runner.AndroidJUnit4;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.TelephonyTest;
import com.android.internal.telephony.euicc.EuiccConnector.GetOtaStatusCommandCallback;
import com.android.internal.telephony.euicc.EuiccConnector.OtaStatusChangedCallback;
import com.android.internal.telephony.flags.FeatureFlags;
import com.android.internal.telephony.uicc.UiccSlot;

import libcore.junit.util.compat.CoreCompatChangeRule.DisableCompatChanges;
import libcore.junit.util.compat.CoreCompatChangeRule.EnableCompatChanges;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.mockito.stubbing.Stubber;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

@RunWith(AndroidJUnit4.class)
public class EuiccControllerTest extends TelephonyTest {
    @Rule
    public TestRule compatChangeRule = new PlatformCompatChangeRule();
    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private static final DownloadableSubscription SUBSCRIPTION =
            DownloadableSubscription.forActivationCode("abcde");

    private static final String PACKAGE_NAME = "test.package";
    private static final String CARRIER_NAME = "test name";
    private static final String TEST_PACKAGE_NAME = "com.android.frameworks.telephonytests";
    private static final byte[] SIGNATURE_BYTES = new byte[] {1, 2, 3, 4, 5};

    private static final UiccAccessRule ACCESS_RULE;
    static {
        try {
            ACCESS_RULE = new UiccAccessRule(
                    MessageDigest.getInstance("SHA-256").digest(SIGNATURE_BYTES),
                    PACKAGE_NAME,
                    0);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 must exist");
        }
    }

    private static final DownloadableSubscription SUBSCRIPTION_WITH_METADATA =
            DownloadableSubscription.forActivationCode("abcde");
    static {
        SUBSCRIPTION_WITH_METADATA.setCarrierName("test name");
        SUBSCRIPTION_WITH_METADATA.setAccessRules(
                Arrays.asList(new UiccAccessRule[] { ACCESS_RULE }));
    }

    private static final String OS_VERSION = "1.0";
    private static final EuiccInfo EUICC_INFO = new EuiccInfo(OS_VERSION);

    private static final int SUBSCRIPTION_ID = 12345;
    private static final String ICC_ID = "54321";
    private static final int CARD_ID = 25;
    private static final int REMOVABLE_CARD_ID = 26;
    private static final long AVAILABLE_MEMORY = 123L;

    // Mocked classes
    private EuiccConnector mMockConnector;
    private UiccSlot mUiccSlot;

    private TestEuiccController mController;
    private int mSavedEuiccProvisionedValue;

    private static class TestEuiccController extends EuiccController {
        // Captured arguments to addResolutionIntent
        private String mResolutionAction;
        private EuiccOperation mOp;

        // Captured arguments to sendResult.
        private PendingIntent mCallbackIntent;
        private int mResultCode;
        private Intent mExtrasIntent;

        // Whether refreshSubscriptionsAndSendResult was called.
        private boolean mCalledRefreshSubscriptionsAndSendResult;

        // Number of OTA status changed.
        private int mNumOtaStatusChanged;

        TestEuiccController(Context context, EuiccConnector connector, FeatureFlags featureFlags) {
            super(context, connector, featureFlags);
            mNumOtaStatusChanged = 0;
        }

        @Override
        public void addResolutionIntent(
                Intent extrasIntent, String resolutionAction, String callingPackage,
                int resolvableErrors, boolean confirmationCodeRetried, EuiccOperation op,
                int cardId, int portIndex, boolean usePortIndex, int subscriptionId) {
            mResolutionAction = resolutionAction;
            mOp = op;
        }

        @Override
        public void sendResult(PendingIntent callbackIntent, int resultCode, Intent extrasIntent) {
            assertNull("sendResult called twice unexpectedly", mCallbackIntent);
            mCallbackIntent = callbackIntent;
            mResultCode = resultCode;
            mExtrasIntent = extrasIntent;
        }

        @Override
        public void refreshSubscriptionsAndSendResult(
                PendingIntent callbackIntent, int resultCode, Intent extrasIntent) {
            mCalledRefreshSubscriptionsAndSendResult = true;
            sendResult(callbackIntent, resultCode, extrasIntent);
        }

        @Override
        public void refreshSubscriptionsAndSendResult(
                PendingIntent callbackIntent,
                int resultCode,
                Intent extrasIntent,
                boolean isCallerAdmin,
                String callingPackage,
                int cardId,
                Set<Integer> subscriptions) {
            mCalledRefreshSubscriptionsAndSendResult = true;
            sendResult(callbackIntent, resultCode, extrasIntent);
        }

        @Override
        public void sendOtaStatusChangedBroadcast() {
            ++mNumOtaStatusChanged;
        }
    }

    @Before
    public void setUp() throws Exception {
        super.setUp(getClass().getSimpleName());
        mMockConnector = Mockito.mock(EuiccConnector.class);
        mUiccSlot = Mockito.mock(UiccSlot.class);
        mController = new TestEuiccController(mContext, mMockConnector, mFeatureFlags);

        PackageInfo pi = new PackageInfo();
        pi.packageName = PACKAGE_NAME;
        pi.signatures = new Signature[] { new Signature(SIGNATURE_BYTES) };
        when(mPackageManager.getPackageInfo(eq(PACKAGE_NAME), anyInt())).thenReturn(pi);

        mSavedEuiccProvisionedValue = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.EUICC_PROVISIONED, 0);
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.EUICC_PROVISIONED, 0);
        setHasManageDevicePolicyManagedSubscriptionsPermission(false);
    }

    @After
    public void tearDown() throws Exception {
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.EUICC_PROVISIONED, mSavedEuiccProvisionedValue);
        mController = null;
        super.tearDown();
    }

    @Test(expected = SecurityException.class)
    public void testGetEid_noPrivileges() throws Exception {
        setGetEidPermissions(false /* hasPhoneStatePrivileged */, false /* hasCarrierPrivileges */);
        callGetEid(true /* success */, "ABCDE" /* eid */, CARD_ID);
    }

    @Test
    public void testGetEid_withPhoneStatePrivileged() throws Exception {
        setGetEidPermissions(true /* hasPhoneStatePrivileged */, false /* hasCarrierPrivileges */);
        assertEquals("ABCDE", callGetEid(true /* success */, "ABCDE" /* eid */, CARD_ID));
    }

    @Test
    public void testGetEid_withCarrierPrivileges() throws Exception {
        setGetEidPermissions(false /* hasPhoneStatePrivileged */, true /* hasCarrierPrivileges */);
        assertEquals("ABCDE", callGetEid(true /* success */, "ABCDE" /* eid */, CARD_ID));
    }

    @Test
    public void testGetEid_failure() throws Exception {
        setGetEidPermissions(true /* hasPhoneStatePrivileged */, false /* hasCarrierPrivileges */);
        assertNull(callGetEid(false /* success */, null /* eid */, CARD_ID));
    }

    @Test
    public void testGetEid_nullReturnValue() throws Exception {
        setGetEidPermissions(true /* hasPhoneStatePrivileged */, false /* hasCarrierPrivileges */);
        assertNull(callGetEid(true /* success */, null /* eid */, CARD_ID));
    }

    @Test
    public void testGetEid_unsupportedCardId() throws Exception {
        setGetEidPermissions(false /* hasPhoneStatePrivileged */, true /* hasCarrierPrivileges */);
        assertEquals("ABCDE", callGetEid(true /* success */, "ABCDE" /* eid */,
                TelephonyManager.UNSUPPORTED_CARD_ID));
    }

    @Test(expected = SecurityException.class)
    public void testGetAvailableMemoryInBytes_noPrivileges() throws Exception {
        setGetAvailableMemoryInBytesPermissions(
                false /* hasPhoneState */,
                false /* hasPhoneStatePrivileged */,
                false /* hasCarrierPrivileges */);
        callGetAvailableMemoryInBytes(AvailableMemoryCallbackStatus.SUCCESS,
                AVAILABLE_MEMORY, CARD_ID);
    }

    @Test
    public void testGetAvailableMemoryInBytes_withPhoneState() throws Exception {
        setGetAvailableMemoryInBytesPermissions(
                true /* hasPhoneState */,
                false /* hasPhoneStatePrivileged */,
                false /* hasCarrierPrivileges */);
        assertEquals(
                AVAILABLE_MEMORY,
                callGetAvailableMemoryInBytes(AvailableMemoryCallbackStatus.SUCCESS,
                        AVAILABLE_MEMORY, CARD_ID));
    }

    @Test
    public void testGetAvailableMemoryInBytes_withPhoneStatePrivileged() throws Exception {
        setGetAvailableMemoryInBytesPermissions(
                false /* hasPhoneState */,
                true /* hasPhoneStatePrivileged */,
                false /* hasCarrierPrivileges */);
        assertEquals(
                AVAILABLE_MEMORY,
                callGetAvailableMemoryInBytes(AvailableMemoryCallbackStatus.SUCCESS,
                        AVAILABLE_MEMORY, CARD_ID));
    }

    @Test
    public void testGetAvailableMemoryInBytes_withCarrierPrivileges() throws Exception {
        setGetAvailableMemoryInBytesPermissions(
                false /* hasPhoneState */,
                false /* hasPhoneStatePrivileged */,
                true /* hasCarrierPrivileges */);
        assertEquals(
                AVAILABLE_MEMORY,
                callGetAvailableMemoryInBytes(AvailableMemoryCallbackStatus.SUCCESS,
                        AVAILABLE_MEMORY, CARD_ID));
    }

    @Test
    public void testGetAvailableMemoryInBytes_failure() throws Exception {
        setGetAvailableMemoryInBytesPermissions(
                true /* hasPhoneState */,
                false /* hasPhoneStatePrivileged */,
                false /* hasCarrierPrivileges */);
        assertEquals(
                EuiccManager.EUICC_MEMORY_FIELD_UNAVAILABLE,
                callGetAvailableMemoryInBytes(AvailableMemoryCallbackStatus.UNAVAILABLE,
                        AVAILABLE_MEMORY, CARD_ID));
    }

    @Test
    public void testGetAvailableMemoryInBytes_exception() throws Exception {
        setGetAvailableMemoryInBytesPermissions(
                true /* hasPhoneState */,
                false /* hasPhoneStatePrivileged */,
                false /* hasCarrierPrivileges */);
        assertThrows(UnsupportedOperationException.class, () -> callGetAvailableMemoryInBytes(
                AvailableMemoryCallbackStatus.EXCEPTION,
                AVAILABLE_MEMORY, CARD_ID));
    }

    @Test
    public void testGetAvailableMemoryInBytes_unsupportedCardId() throws Exception {
        setGetAvailableMemoryInBytesPermissions(
                false /* hasPhoneState */,
                false /* hasPhoneStatePrivileged */,
                true /* hasCarrierPrivileges */);
        assertEquals(
                AVAILABLE_MEMORY,
                callGetAvailableMemoryInBytes(
                        AvailableMemoryCallbackStatus.SUCCESS,
                        AVAILABLE_MEMORY,
                        TelephonyManager.UNSUPPORTED_CARD_ID));
    }

    @Test(expected = SecurityException.class)
    public void testGetOtaStatus_noPrivileges() {
        setHasWriteEmbeddedPermission(false /* hasPermission */);
        callGetOtaStatus(true /* success */, 1 /* status */);
    }

    @Test
    public void testGetOtaStatus_withWriteEmbeddedPermission() {
        setHasWriteEmbeddedPermission(true /* hasPermission */);
        assertEquals(1, callGetOtaStatus(true /* success */, 1 /* status */));
    }

    @Test
    public void testGetOtaStatus_failure() {
        setHasWriteEmbeddedPermission(true /* hasPermission */);
        assertEquals(
                EUICC_OTA_STATUS_UNAVAILABLE,
                callGetOtaStatus(false /* success */, 1 /* status */));
    }

    @Test
    public void testStartOtaUpdatingIfNecessary_serviceNotAvailable() {
        setHasWriteEmbeddedPermission(true /* hasPermission */);
        callStartOtaUpdatingIfNecessary(
                false /* serviceAvailable */, EuiccManager.EUICC_OTA_IN_PROGRESS);
        assertEquals(mController.mNumOtaStatusChanged, 0);
    }

    @Test
    public void testStartOtaUpdatingIfNecessary_otaStatusChanged() {
        setHasWriteEmbeddedPermission(true /* hasPermission */);
        callStartOtaUpdatingIfNecessary(
                true /* serviceAvailable */, EuiccManager.EUICC_OTA_IN_PROGRESS);
        callStartOtaUpdatingIfNecessary(
                true /* serviceAvailable */, EuiccManager.EUICC_OTA_FAILED);
        callStartOtaUpdatingIfNecessary(
                true /* serviceAvailable */, EuiccManager.EUICC_OTA_SUCCEEDED);
        callStartOtaUpdatingIfNecessary(
                true /* serviceAvailable */, EuiccManager.EUICC_OTA_NOT_NEEDED);
        callStartOtaUpdatingIfNecessary(
                true /* serviceAvailable */, EuiccManager.EUICC_OTA_STATUS_UNAVAILABLE);

        assertEquals(mController.mNumOtaStatusChanged, 5);
    }


    @Test
    public void testGetEuiccInfo_success() {
        assertEquals(OS_VERSION, callGetEuiccInfo(true /* success */, EUICC_INFO).getOsVersion());
    }

    @Test
    public void testGetEuiccInfo_failure() {
        assertNull(callGetEuiccInfo(false /* success */, null /* euiccInfo */));
    }

    @Test
    public void testGetEuiccInfo_nullReturnValue() {
        assertNull(callGetEuiccInfo(true /* success */, null /* euiccInfo */));
    }

    @Test
    public void testGetDownloadableSubscriptionMetadata_serviceUnavailable() throws Exception {
        setHasWriteEmbeddedPermission(true);
        callGetDownloadableSubscriptionMetadata(
                SUBSCRIPTION, false /* complete */, null /* result */);
        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_ERROR,
                0 /* detailedCode */);
        verify(mMockConnector).getDownloadableSubscriptionMetadata(anyInt(), anyInt(), any(),
                anyBoolean(), anyBoolean(), any());
    }

    @Test
    public void testGetDownloadableSubscriptionMetadata_error() throws Exception {
        setHasWriteEmbeddedPermission(true);
        GetDownloadableSubscriptionMetadataResult result =
                new GetDownloadableSubscriptionMetadataResult(42, null /* subscription */);
        callGetDownloadableSubscriptionMetadata(SUBSCRIPTION, true /* complete */, result);
        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_ERROR,
                42 /* detailedCode */);
    }

    @Test
    public void testGetDownloadableSubscriptionMetadata_mustDeactivateSim()
            throws Exception {
        setHasWriteEmbeddedPermission(true);
        GetDownloadableSubscriptionMetadataResult result =
                new GetDownloadableSubscriptionMetadataResult(
                        EuiccService.RESULT_MUST_DEACTIVATE_SIM, null /* subscription */);
        callGetDownloadableSubscriptionMetadata(SUBSCRIPTION, true /* complete */, result);
        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_RESOLVABLE_ERROR,
                0 /* detailedCode */);
        verifyResolutionIntent(EuiccService.ACTION_RESOLVE_DEACTIVATE_SIM,
                EuiccOperation.ACTION_GET_METADATA_DEACTIVATE_SIM);
    }

    @Test
    public void testGetDownloadableSubscriptionMetadata_success() throws Exception {
        setHasWriteEmbeddedPermission(true);
        GetDownloadableSubscriptionMetadataResult result =
                new GetDownloadableSubscriptionMetadataResult(
                        EuiccService.RESULT_OK, SUBSCRIPTION_WITH_METADATA);
        callGetDownloadableSubscriptionMetadata(SUBSCRIPTION, true /* complete */, result);
        Intent intent = verifyIntentSent(
                EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_OK, 0 /* detailedCode */);
        DownloadableSubscription receivedSubscription = intent.getParcelableExtra(
                EuiccManager.EXTRA_EMBEDDED_SUBSCRIPTION_DOWNLOADABLE_SUBSCRIPTION);
        assertNotNull(receivedSubscription);
        assertEquals(CARRIER_NAME, receivedSubscription.getCarrierName());
    }

    @Test
    public void testGetDefaultDownloadableSubscriptionList_serviceUnavailable() throws Exception {
        setHasWriteEmbeddedPermission(true);
        callGetDefaultDownloadableSubscriptionList(false /* complete */, null /* result */);
        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_ERROR,
                0 /* detailedCode */);
    }

    @Test
    public void testGetDefaultDownloadableSubscriptionList_error() throws Exception {
        setHasWriteEmbeddedPermission(true);
        GetDefaultDownloadableSubscriptionListResult result =
                new GetDefaultDownloadableSubscriptionListResult(42, null /* subscriptions */);
        callGetDefaultDownloadableSubscriptionList(true /* complete */, result);
        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_ERROR,
                42 /* detailedCode */);
        verify(mMockConnector).getDefaultDownloadableSubscriptionList(anyInt(), anyBoolean(),
                any());
    }

    @Test
    public void testGetDefaultDownloadableSubscriptionList_mustDeactivateSim()
            throws Exception {
        setHasWriteEmbeddedPermission(true);
        GetDefaultDownloadableSubscriptionListResult result =
                new GetDefaultDownloadableSubscriptionListResult(
                        EuiccService.RESULT_MUST_DEACTIVATE_SIM, null /* subscriptions */);
        callGetDefaultDownloadableSubscriptionList(true /* complete */, result);
        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_RESOLVABLE_ERROR,
                0 /* detailedCode */);
        verifyResolutionIntent(EuiccService.ACTION_RESOLVE_DEACTIVATE_SIM,
                EuiccOperation.ACTION_GET_DEFAULT_LIST_DEACTIVATE_SIM);
    }

    @Test
    public void testGetDefaultDownloadableSubscriptionList_success() throws Exception {
        setHasWriteEmbeddedPermission(true);
        GetDefaultDownloadableSubscriptionListResult result =
                new GetDefaultDownloadableSubscriptionListResult(
                        EuiccService.RESULT_OK,
                        new DownloadableSubscription[] { SUBSCRIPTION_WITH_METADATA });
        callGetDefaultDownloadableSubscriptionList(true /* complete */, result);
        Intent intent = verifyIntentSent(
                EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_OK, 0 /* detailedCode */);
        Parcelable[] receivedSubscriptions = intent.getParcelableArrayExtra(
                EuiccManager.EXTRA_EMBEDDED_SUBSCRIPTION_DOWNLOADABLE_SUBSCRIPTIONS);
        assertNotNull(receivedSubscriptions);
        assertEquals(1, receivedSubscriptions.length);
        assertEquals(CARRIER_NAME,
                ((DownloadableSubscription) receivedSubscriptions[0]).getCarrierName());
    }

    @Test
    @DisableCompatChanges({EuiccManager.SHOULD_RESOLVE_PORT_INDEX_FOR_APPS})
    public void testDownloadSubscription_serviceUnavailable() throws Exception {
        setHasWriteEmbeddedPermission(true);
        setUpUiccSlotData();
        callDownloadSubscription(
                SUBSCRIPTION, true /* switchAfterDownload */, false /* complete */,
                0 /* result */,  0 /* resolvableError */, "whatever" /* callingPackage */);
        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_ERROR,
                0 /* detailedCode */);
        verify(mMockConnector).downloadSubscription(anyInt(), anyInt(),
                    any(), anyBoolean(), anyBoolean(), any(), any());
    }

    @Test
    @DisableCompatChanges({EuiccManager.SHOULD_RESOLVE_PORT_INDEX_FOR_APPS})
    public void testDownloadSubscription_error() throws Exception {
        setHasWriteEmbeddedPermission(true);
        callDownloadSubscription(SUBSCRIPTION, false /* switchAfterDownload */, true /* complete */,
                42,  0 /* resolvableError */, "whatever" /* callingPackage */);
        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_ERROR,
                42 /* detailedCode */);
    }

    @Test
    @DisableCompatChanges({EuiccManager.SHOULD_RESOLVE_PORT_INDEX_FOR_APPS})
    public void testDownloadSubscription_mustDeactivateSim() throws Exception {
        setHasWriteEmbeddedPermission(true);
        callDownloadSubscription(SUBSCRIPTION, false /* switchAfterDownload */, true /* complete */,
                EuiccService.RESULT_MUST_DEACTIVATE_SIM, 0 /* resolvableError */,
                "whatever" /* callingPackage */);
        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_RESOLVABLE_ERROR,
                0 /* detailedCode */);
        verifyResolutionIntent(EuiccService.ACTION_RESOLVE_DEACTIVATE_SIM,
                EuiccOperation.ACTION_DOWNLOAD_DEACTIVATE_SIM);
    }

    @Test
    @DisableCompatChanges({EuiccManager.SHOULD_RESOLVE_PORT_INDEX_FOR_APPS})
    public void testDownloadSubscription_needConfirmationCode() throws Exception {
        setHasWriteEmbeddedPermission(true);
        callDownloadSubscription(SUBSCRIPTION, false /* switchAfterDownload */, true /* complete */,
                EuiccService.RESULT_RESOLVABLE_ERRORS, 0b01 /* resolvableError */,
                "whatever" /* callingPackage */);
        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_RESOLVABLE_ERROR,
                0 /* detailedCode */);
        verifyResolutionIntent(EuiccService.ACTION_RESOLVE_RESOLVABLE_ERRORS,
                EuiccOperation.ACTION_DOWNLOAD_RESOLVABLE_ERRORS);
    }

    @Test
    @DisableCompatChanges({EuiccManager.SHOULD_RESOLVE_PORT_INDEX_FOR_APPS})
    public void testDownloadSubscription_success() throws Exception {
        setHasWriteEmbeddedPermission(true);
        setUpUiccSlotData();
        callDownloadSubscription(SUBSCRIPTION, true /* switchAfterDownload */, true /* complete */,
                EuiccService.RESULT_OK, 0 /* resolvableError */, "whatever" /* callingPackage */);
        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_OK, 0 /* detailedCode */);
        // switchAfterDownload = true so no refresh should occur.
        assertFalse(mController.mCalledRefreshSubscriptionsAndSendResult);
    }

    @Test
    @DisableCompatChanges({EuiccManager.SHOULD_RESOLVE_PORT_INDEX_FOR_APPS})
    public void testDownloadSubscription_noSwitch_success() throws Exception {
        setHasWriteEmbeddedPermission(true);
        callDownloadSubscription(SUBSCRIPTION, false /* switchAfterDownload */, true /* complete */,
                EuiccService.RESULT_OK, 0 /* resolvableError */, "whatever" /* callingPackage */);
        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_OK, 0 /* detailedCode */);
        assertTrue(mController.mCalledRefreshSubscriptionsAndSendResult);
    }

    @Test
    @DisableCompatChanges({EuiccManager.SHOULD_RESOLVE_PORT_INDEX_FOR_APPS})
    public void testDownloadSubscription_noPrivileges_getMetadata_serviceUnavailable()
            throws Exception {
        setHasWriteEmbeddedPermission(false);
        setUpUiccSlotData();
        prepareGetDownloadableSubscriptionMetadataCall(false /* complete */, null /* result */);
        callDownloadSubscription(SUBSCRIPTION, true /* switchAfterDownload */, true /* complete */,
                12345, 0 /* resolvableError */, PACKAGE_NAME /* callingPackage */);
        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_RESOLVABLE_ERROR,
                0 /* detailedCode */);
        verify(mMockConnector, never()).downloadSubscription(anyInt(), anyInt(),
                any(), anyBoolean(), anyBoolean(), any(), any());
    }

    @Test
    @DisableCompatChanges({EuiccManager.SHOULD_RESOLVE_PORT_INDEX_FOR_APPS})
    public void testDownloadSubscription_noPrivileges_getMetadata_serviceUnavailable_canManageSim()
            throws Exception {
        setHasWriteEmbeddedPermission(false);
        setUpUiccSlotData();
        prepareGetDownloadableSubscriptionMetadataCall(false /* complete */, null /* result */);
        setCanManageSubscriptionOnTargetSim(true /* isTargetEuicc */, true /* hasPrivileges */);
        callDownloadSubscription(SUBSCRIPTION, true /* switchAfterDownload */, true /* complete */,
                12345, 0 /* resolvableError */, PACKAGE_NAME /* callingPackage */);
        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_ERROR,
                0 /* detailedCode */);
        verify(mMockConnector, never()).downloadSubscription(anyInt(), anyInt(),
                any(), anyBoolean(), anyBoolean(), any(), any());
    }

    @Test
    @DisableCompatChanges({EuiccManager.SHOULD_RESOLVE_PORT_INDEX_FOR_APPS})
    public void testDownloadSubscription_noPrivileges_getMetadata_error()
            throws Exception {
        setHasWriteEmbeddedPermission(false);
        setUpUiccSlotData();
        GetDownloadableSubscriptionMetadataResult result =
                new GetDownloadableSubscriptionMetadataResult(42, null /* subscription */);
        prepareGetDownloadableSubscriptionMetadataCall(true /* complete */, result);
        callDownloadSubscription(SUBSCRIPTION, true /* switchAfterDownload */, true /* complete */,
                12345, 0 /* resolvableError */, PACKAGE_NAME /* callingPackage */);
        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_RESOLVABLE_ERROR,
                0 /* detailedCode */);
        verify(mMockConnector, never()).downloadSubscription(anyInt(), anyInt(),
                any(), anyBoolean(), anyBoolean(), any(), any());
    }

    @Test
    @DisableCompatChanges({EuiccManager.SHOULD_RESOLVE_PORT_INDEX_FOR_APPS})
    public void testDownloadSubscription_noPrivileges_getMetadata_error_canManageSim()
            throws Exception {
        setHasWriteEmbeddedPermission(false);
        setUpUiccSlotData();
        GetDownloadableSubscriptionMetadataResult result =
                new GetDownloadableSubscriptionMetadataResult(42, null /* subscription */);
        prepareGetDownloadableSubscriptionMetadataCall(true /* complete */, result);
        setCanManageSubscriptionOnTargetSim(true /* isTargetEuicc */, true /* hasPrivileges */);
        callDownloadSubscription(SUBSCRIPTION, true /* switchAfterDownload */, true /* complete */,
                12345, 0 /* resolvableError */, PACKAGE_NAME /* callingPackage */);
        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_ERROR,
                42 /* detailedCode */);
        verify(mMockConnector, never()).downloadSubscription(anyInt(), anyInt(),
                any(), anyBoolean(), anyBoolean(), any(), any());
    }

    @Test
    @DisableCompatChanges({EuiccManager.SHOULD_RESOLVE_PORT_INDEX_FOR_APPS})
    public void testDownloadSubscription_noPrivileges_getMetadata_mustDeactivateSim()
            throws Exception {
        setHasWriteEmbeddedPermission(false);
        setUpUiccSlotData();
        GetDownloadableSubscriptionMetadataResult result =
                new GetDownloadableSubscriptionMetadataResult(
                        EuiccService.RESULT_MUST_DEACTIVATE_SIM, null /* subscription */);
        prepareGetDownloadableSubscriptionMetadataCall(true /* complete */, result);
        callDownloadSubscription(SUBSCRIPTION, true /* switchAfterDownload */, true /* complete */,
                12345, 0 /* resolvableError */, PACKAGE_NAME /* callingPackage */);
        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_RESOLVABLE_ERROR,
                0 /* detailedCode */);
        // In this case we go with the potentially stronger NO_PRIVILEGES consent dialog to avoid
        // double prompting.
        verifyResolutionIntent(EuiccService.ACTION_RESOLVE_NO_PRIVILEGES,
                EuiccOperation.ACTION_DOWNLOAD_NO_PRIVILEGES_OR_DEACTIVATE_SIM_CHECK_METADATA);
    }

    @Test
    @DisableCompatChanges({EuiccManager.SHOULD_RESOLVE_PORT_INDEX_FOR_APPS})
    public void testDownloadSubscription_noPrivileges_getMetadata_mustDeactivateSim_canManageSim()
            throws Exception {
        setHasWriteEmbeddedPermission(false);
        setUpUiccSlotData();
        GetDownloadableSubscriptionMetadataResult result =
                new GetDownloadableSubscriptionMetadataResult(
                    EuiccService.RESULT_MUST_DEACTIVATE_SIM, null /* subscription */);
        prepareGetDownloadableSubscriptionMetadataCall(true /* complete */, result);
        setCanManageSubscriptionOnTargetSim(true /* isTargetEuicc */, true /* hasPrivileges */);
        callDownloadSubscription(SUBSCRIPTION, true /* switchAfterDownload */, true /* complete */,
                12345, 0 /* resolvableError */, PACKAGE_NAME /* callingPackage */);
        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_RESOLVABLE_ERROR,
                0 /* detailedCode */);
        // In this case we go with the potentially stronger NO_PRIVILEGES consent dialog to avoid
        // double prompting.
        verifyResolutionIntent(EuiccService.ACTION_RESOLVE_DEACTIVATE_SIM,
                EuiccOperation.ACTION_DOWNLOAD_NO_PRIVILEGES_OR_DEACTIVATE_SIM_CHECK_METADATA);
    }

    @Test
    @DisableCompatChanges({EuiccManager.SHOULD_RESOLVE_PORT_INDEX_FOR_APPS})
    public void testDownloadSubscription_noPrivileges_hasCarrierPrivileges() throws Exception {
        setHasWriteEmbeddedPermission(false);
        setUpUiccSlotData();
        GetDownloadableSubscriptionMetadataResult result =
                new GetDownloadableSubscriptionMetadataResult(
                        EuiccService.RESULT_OK, SUBSCRIPTION_WITH_METADATA);
        prepareGetDownloadableSubscriptionMetadataCall(true /* complete */, result);
        when(mTelephonyManager.getPhoneCount()).thenReturn(1);
        setHasCarrierPrivilegesOnActiveSubscription(true);
        callDownloadSubscription(SUBSCRIPTION, true /* switchAfterDownload */, true /* complete */,
                EuiccService.RESULT_OK, 0 /* resolvableError */, PACKAGE_NAME /* callingPackage */);
        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_OK, 0 /* detailedCode */);
        // switchAfterDownload = true so no refresh should occur.
        assertFalse(mController.mCalledRefreshSubscriptionsAndSendResult);
    }

    @Test
    @DisableCompatChanges({EuiccManager.SHOULD_RESOLVE_PORT_INDEX_FOR_APPS})
    public void testDownloadSubscription_noPrivileges_hasCarrierPrivileges_multiSim()
            throws Exception {
        setHasWriteEmbeddedPermission(false);
        setUpUiccSlotData();
        GetDownloadableSubscriptionMetadataResult result =
                new GetDownloadableSubscriptionMetadataResult(
                    EuiccService.RESULT_OK, SUBSCRIPTION_WITH_METADATA);
        prepareGetDownloadableSubscriptionMetadataCall(true /* complete */, result);
        when(mTelephonyManager.getPhoneCount()).thenReturn(2);
        setCanManageSubscriptionOnTargetSim(true /* isTargetEuicc */, true /* hasPrivileges */);
        callDownloadSubscription(SUBSCRIPTION, true /* switchAfterDownload */, true /* complete */,
                EuiccService.RESULT_OK, 0 /* resolvableError */, PACKAGE_NAME /* callingPackage */);
        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_OK, 0 /* detailedCode */);
        // switchAfterDownload = true so no refresh should occur.
        assertFalse(mController.mCalledRefreshSubscriptionsAndSendResult);
    }

    @Test
    @DisableCompatChanges({EuiccManager.SHOULD_RESOLVE_PORT_INDEX_FOR_APPS})
    public void testDownloadSubscription_noPrivileges_hasCarrierPrivileges_needsConsent()
            throws Exception {
        setHasWriteEmbeddedPermission(false);
        setUpUiccSlotData();
        GetDownloadableSubscriptionMetadataResult result =
                new GetDownloadableSubscriptionMetadataResult(
                        EuiccService.RESULT_OK, SUBSCRIPTION_WITH_METADATA);
        prepareGetDownloadableSubscriptionMetadataCall(true /* complete */, result);
        when(mTelephonyManager.getPhoneCount()).thenReturn(1);
        setHasCarrierPrivilegesOnActiveSubscription(false);
        callDownloadSubscription(SUBSCRIPTION, true /* switchAfterDownload */, true /* complete */,
                12345, 0 /* resolvableError */, PACKAGE_NAME /* callingPackage */);
        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_RESOLVABLE_ERROR,
                0 /* detailedCode */);
        verify(mMockConnector, never()).downloadSubscription(anyInt(), anyInt(),
                any(), anyBoolean(), anyBoolean(), any(), any());
        verifyResolutionIntent(EuiccService.ACTION_RESOLVE_NO_PRIVILEGES,
                EuiccOperation.ACTION_DOWNLOAD_NO_PRIVILEGES_OR_DEACTIVATE_SIM_CHECK_METADATA);
    }

    @Test
    @DisableCompatChanges({EuiccManager.SHOULD_RESOLVE_PORT_INDEX_FOR_APPS})
    public void testDownloadSubscription_noPrivileges_hasCarrierPrivileges_needsConsent_multiSim()
            throws Exception {
        setHasWriteEmbeddedPermission(false);
        setUpUiccSlotData();
        GetDownloadableSubscriptionMetadataResult result =
                new GetDownloadableSubscriptionMetadataResult(
                    EuiccService.RESULT_OK, SUBSCRIPTION_WITH_METADATA);
        prepareGetDownloadableSubscriptionMetadataCall(true /* complete */, result);
        when(mTelephonyManager.getPhoneCount()).thenReturn(2);
        setCanManageSubscriptionOnTargetSim(true /* isTargetEuicc */, false /* hasPrivileges */);
        callDownloadSubscription(SUBSCRIPTION, true /* switchAfterDownload */, true /* complete */,
                12345, 0 /* resolvableError */, PACKAGE_NAME /* callingPackage */);
        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_RESOLVABLE_ERROR,
                0 /* detailedCode */);
        verify(mMockConnector, never()).downloadSubscription(anyInt(), anyInt(),
                any(), anyBoolean(), anyBoolean(), any(), any());
        verifyResolutionIntent(EuiccService.ACTION_RESOLVE_NO_PRIVILEGES,
                EuiccOperation.ACTION_DOWNLOAD_NO_PRIVILEGES_OR_DEACTIVATE_SIM_CHECK_METADATA);
    }

    @Test
    @DisableCompatChanges({EuiccManager.SHOULD_RESOLVE_PORT_INDEX_FOR_APPS})
    public void testDownloadSubscription_noPriv_hasCarrierPrivi_needsConsent_multiSim_targetPsim()
            throws Exception {
        setHasWriteEmbeddedPermission(false);
        setUpUiccSlotData();
        GetDownloadableSubscriptionMetadataResult result =
                new GetDownloadableSubscriptionMetadataResult(
                    EuiccService.RESULT_OK, SUBSCRIPTION_WITH_METADATA);
        prepareGetDownloadableSubscriptionMetadataCall(true /* complete */, result);
        when(mTelephonyManager.getPhoneCount()).thenReturn(2);
        setCanManageSubscriptionOnTargetSim(false /* isTargetEuicc */, true /* hasPrivileges */);
        callDownloadSubscription(SUBSCRIPTION, true /* switchAfterDownload */, true /* complete */,
                12345, 0 /* resolvableError */, PACKAGE_NAME /* callingPackage */);
        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_RESOLVABLE_ERROR,
                0 /* detailedCode */);
        verify(mMockConnector, never()).downloadSubscription(anyInt(), anyInt(),
                any(), anyBoolean(), anyBoolean(), any(), any());
        verifyResolutionIntent(EuiccService.ACTION_RESOLVE_NO_PRIVILEGES,
                EuiccOperation.ACTION_DOWNLOAD_NO_PRIVILEGES_OR_DEACTIVATE_SIM_CHECK_METADATA);
    }

    @Test
    @DisableCompatChanges({EuiccManager.SHOULD_RESOLVE_PORT_INDEX_FOR_APPS})
    public void testDownloadSubscription_noPrivileges_noCarrierPrivileges() throws Exception {
        setHasWriteEmbeddedPermission(false);
        setUpUiccSlotData();
        GetDownloadableSubscriptionMetadataResult result =
                new GetDownloadableSubscriptionMetadataResult(
                        EuiccService.RESULT_OK, SUBSCRIPTION_WITH_METADATA);
        prepareGetDownloadableSubscriptionMetadataCall(true /* complete */, result);
        PackageInfo pi = new PackageInfo();
        pi.packageName = PACKAGE_NAME;
        pi.signatures = new Signature[] { new Signature(new byte[] { 5, 4, 3, 2, 1 }) };
        when(mPackageManager.getPackageInfo(eq(PACKAGE_NAME), anyInt())).thenReturn(pi);
        callDownloadSubscription(SUBSCRIPTION, true /* switchAfterDownload */, true /* complete */,
                12345, 0 /* resolvableError */, PACKAGE_NAME /* callingPackage */);
        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_RESOLVABLE_ERROR,
                0 /* detailedCode */);
        verify(mTelephonyManager, never()).checkCarrierPrivilegesForPackage(PACKAGE_NAME);
        verify(mMockConnector, never()).downloadSubscription(anyInt(), anyInt(),
                any(), anyBoolean(), anyBoolean(), any(), any());
    }

    @Test
    @DisableCompatChanges({EuiccManager.SHOULD_RESOLVE_PORT_INDEX_FOR_APPS})
    public void testDownloadSubscription_noPrivileges_noCarrierPrivileges_canManagerTargetSim()
            throws Exception {
        setHasWriteEmbeddedPermission(false);
        setUpUiccSlotData();
        GetDownloadableSubscriptionMetadataResult result =
                new GetDownloadableSubscriptionMetadataResult(
                    EuiccService.RESULT_OK, SUBSCRIPTION_WITH_METADATA);
        prepareGetDownloadableSubscriptionMetadataCall(true /* complete */, result);
        PackageInfo pi = new PackageInfo();
        pi.packageName = PACKAGE_NAME;
        pi.signatures = new Signature[] { new Signature(new byte[] { 5, 4, 3, 2, 1 }) };
        when(mPackageManager.getPackageInfo(eq(PACKAGE_NAME), anyInt())).thenReturn(pi);
        setCanManageSubscriptionOnTargetSim(true /* isTargetEuicc */, true /* hasPrivileges */);
        callDownloadSubscription(SUBSCRIPTION, true /* switchAfterDownload */, true /* complete */,
                12345, 0 /* resolvableError */, PACKAGE_NAME /* callingPackage */);
        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_ERROR,
                0 /* detailedCode */);
        verify(mTelephonyManager, never()).checkCarrierPrivilegesForPackage(PACKAGE_NAME);
        verify(mMockConnector, never()).downloadSubscription(anyInt(), anyInt(),
                any(), anyBoolean(), anyBoolean(), any(), any());
    }

    @Test
    @DisableCompatChanges({EuiccManager.SHOULD_RESOLVE_PORT_INDEX_FOR_APPS})
    public void testDownloadSubscription_noAdminPermission()
            throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_ESIM_MANAGEMENT_ENABLED);
        setHasWriteEmbeddedPermission(false);
        setHasManageDevicePolicyManagedSubscriptionsPermission(false);
        setUpUiccSlotData();
        GetDownloadableSubscriptionMetadataResult result =
                new GetDownloadableSubscriptionMetadataResult(
                        EuiccService.RESULT_OK, SUBSCRIPTION_WITH_METADATA);
        prepareGetDownloadableSubscriptionMetadataCall(true /* complete */, result);
        PackageInfo pi = new PackageInfo();
        pi.packageName = PACKAGE_NAME;
        when(mPackageManager.getPackageInfo(eq(PACKAGE_NAME), anyInt())).thenReturn(pi);
        setCanManageSubscriptionOnTargetSim(false /* isTargetEuicc */, false /* hasPrivileges */);

        callDownloadSubscription(SUBSCRIPTION, false /* switchAfterDownload */, true /* complete */,
                12345, 0 /* resolvableError */, PACKAGE_NAME /* callingPackage */);

        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_RESOLVABLE_ERROR,
                0 /* detailedCode */);
        verify(mMockConnector, never()).downloadSubscription(anyInt(), anyInt(),
                any(), anyBoolean(), anyBoolean(), any(), any());
    }

    @Test
    @DisableCompatChanges({EuiccManager.SHOULD_RESOLVE_PORT_INDEX_FOR_APPS})
    public void testDownloadSubscription_adminPermission()
            throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_ESIM_MANAGEMENT_ENABLED);
        setHasManageDevicePolicyManagedSubscriptionsPermission(true);
        setHasWriteEmbeddedPermission(false);

        callDownloadSubscription(SUBSCRIPTION, false /* switchAfterDownload */, true /* complete */,
                EuiccService.RESULT_OK, 0 /* resolvableError */, "whatever" /* callingPackage */);

        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_OK, 0 /* detailedCode */);
        assertTrue(mController.mCalledRefreshSubscriptionsAndSendResult);
    }

    @Test
    @DisableCompatChanges({EuiccManager.SHOULD_RESOLVE_PORT_INDEX_FOR_APPS})
    public void testDownloadSubscription_adminPermission_usingSwitchAfterDownload_fails()
            throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_ESIM_MANAGEMENT_ENABLED);
        setHasWriteEmbeddedPermission(false);
        setHasManageDevicePolicyManagedSubscriptionsPermission(true);
        setUpUiccSlotData();
        GetDownloadableSubscriptionMetadataResult result =
                new GetDownloadableSubscriptionMetadataResult(
                        EuiccService.RESULT_OK, SUBSCRIPTION_WITH_METADATA);
        prepareGetDownloadableSubscriptionMetadataCall(true /* complete */, result);
        PackageInfo pi = new PackageInfo();
        pi.packageName = PACKAGE_NAME;
        when(mPackageManager.getPackageInfo(eq(PACKAGE_NAME), anyInt())).thenReturn(pi);
        setCanManageSubscriptionOnTargetSim(false /* isTargetEuicc */, false /* hasPrivileges */);

        callDownloadSubscription(SUBSCRIPTION, true /* switchAfterDownload */,
                true /* complete */,
                12345, 0 /* resolvableError */, PACKAGE_NAME /* callingPackage */);

        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_ERROR, 0 /* detailedCode */);
        verify(mMockConnector, never()).downloadSubscription(anyInt(), anyInt(),
                any(), anyBoolean(), anyBoolean(), any(), any());
    }

    @Test
    @DisableCompatChanges({EuiccManager.SHOULD_RESOLVE_PORT_INDEX_FOR_APPS})
    public void testDownloadSubscription_profileOwner_usingSwitchAfterDownload_fails()
            throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_ESIM_MANAGEMENT_ENABLED);
        setHasWriteEmbeddedPermission(false);
        setHasManageDevicePolicyManagedSubscriptionsPermission(true);
        setUpUiccSlotData();
        GetDownloadableSubscriptionMetadataResult result =
                new GetDownloadableSubscriptionMetadataResult(EuiccService.RESULT_OK,
                        SUBSCRIPTION_WITH_METADATA);
        doReturn(true).when(mDevicePolicyManager).isProfileOwnerApp(PACKAGE_NAME);
        doReturn(false).when(mDevicePolicyManager).isOrganizationOwnedDeviceWithManagedProfile();
        doReturn(false).when(mDevicePolicyManager).isDeviceOwnerApp(PACKAGE_NAME);
        prepareGetDownloadableSubscriptionMetadataCall(true /* complete */, result);
        PackageInfo pi = new PackageInfo();
        pi.packageName = PACKAGE_NAME;
        when(mPackageManager.getPackageInfo(eq(PACKAGE_NAME), anyInt())).thenReturn(pi);
        setCanManageSubscriptionOnTargetSim(false /* isTargetEuicc */, false /* hasPrivileges */);

        callDownloadSubscription(SUBSCRIPTION, true /* switchAfterDownload */,
                true /* complete */,
                12345, 0 /* resolvableError */, PACKAGE_NAME /* callingPackage */);

        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_ERROR, 0 /* detailedCode */);
        verify(mMockConnector, never()).downloadSubscription(anyInt(), anyInt(), any(),
                anyBoolean(), anyBoolean(), any(), any());
    }

    @Test
    @DisableCompatChanges({EuiccManager.SHOULD_RESOLVE_PORT_INDEX_FOR_APPS})
    public void testDownloadSubscription_orgOwnedProfileOwner_usingSwitchAfterDownload_success()
            throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_ESIM_MANAGEMENT_ENABLED);
        setHasWriteEmbeddedPermission(false);
        setHasManageDevicePolicyManagedSubscriptionsPermission(true);
        setUpUiccSlotData();
        GetDownloadableSubscriptionMetadataResult result =
                new GetDownloadableSubscriptionMetadataResult(EuiccService.RESULT_OK,
                        SUBSCRIPTION_WITH_METADATA);
        doReturn(true).when(mDevicePolicyManager).isProfileOwnerApp(PACKAGE_NAME);
        doReturn(true).when(mDevicePolicyManager).isOrganizationOwnedDeviceWithManagedProfile();
        doReturn(false).when(mDevicePolicyManager).isDeviceOwnerApp(PACKAGE_NAME);
        prepareGetDownloadableSubscriptionMetadataCall(true /* complete */, result);
        PackageInfo pi = new PackageInfo();
        pi.packageName = PACKAGE_NAME;
        when(mPackageManager.getPackageInfo(eq(PACKAGE_NAME), anyInt())).thenReturn(pi);

        callDownloadSubscription(SUBSCRIPTION, true /* switchAfterDownload */, true /* complete */,
                EuiccService.RESULT_OK, 0 /* resolvableError */, PACKAGE_NAME /* callingPackage */);

        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_OK, 0 /* detailedCode */);
        assertFalse(mController.mCalledRefreshSubscriptionsAndSendResult);
    }

    @Test
    @DisableCompatChanges({EuiccManager.SHOULD_RESOLVE_PORT_INDEX_FOR_APPS})
    public void testDownloadSubscription_deviceOwner_usingSwitchAfterDownload_success()
            throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_ESIM_MANAGEMENT_ENABLED);
        setHasWriteEmbeddedPermission(false);
        setHasManageDevicePolicyManagedSubscriptionsPermission(true);
        setUpUiccSlotData();
        GetDownloadableSubscriptionMetadataResult result =
                new GetDownloadableSubscriptionMetadataResult(EuiccService.RESULT_OK,
                        SUBSCRIPTION_WITH_METADATA);
        doReturn(false).when(mDevicePolicyManager).isProfileOwnerApp(PACKAGE_NAME);
        doReturn(false).when(mDevicePolicyManager).isOrganizationOwnedDeviceWithManagedProfile();
        doReturn(true).when(mDevicePolicyManager).isDeviceOwnerApp(PACKAGE_NAME);
        prepareGetDownloadableSubscriptionMetadataCall(true /* complete */, result);
        PackageInfo pi = new PackageInfo();
        pi.packageName = PACKAGE_NAME;
        when(mPackageManager.getPackageInfo(eq(PACKAGE_NAME), anyInt())).thenReturn(pi);

        callDownloadSubscription(SUBSCRIPTION, true /* switchAfterDownload */, true /* complete */,
                EuiccService.RESULT_OK, 0 /* resolvableError */, PACKAGE_NAME /* callingPackage */);

        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_OK, 0 /* detailedCode */);
        assertFalse(mController.mCalledRefreshSubscriptionsAndSendResult);
    }

    @Test
    @DisableCompatChanges({EuiccManager.SHOULD_RESOLVE_PORT_INDEX_FOR_APPS})
    public void testDownloadSubscription_onlyAdminManagedAllowed_callerNotAdmin_error()
            throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_ESIM_MANAGEMENT_ENABLED);
        setHasManageDevicePolicyManagedSubscriptionsPermission(false);
        setHasWriteEmbeddedPermission(true);
        doReturn(true)
                .when(mUserManager)
                .hasUserRestriction(UserManager.DISALLOW_SIM_GLOBALLY);

        callDownloadSubscription(SUBSCRIPTION, false /* switchAfterDownload */, true /* complete */,
                12345, 0 /* resolvableError */, "whatever" /* callingPackage */);

        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_ERROR, 0 /* detailedCode */);
        assertFalse(mController.mCalledRefreshSubscriptionsAndSendResult);
    }

    @Test
    @DisableCompatChanges({EuiccManager.SHOULD_RESOLVE_PORT_INDEX_FOR_APPS})
    public void testDownloadSubscription_onlyAdminManagedAllowed_callerNotAdmin_disabled_success()
            throws Exception {
        mSetFlagsRule.disableFlags(Flags.FLAG_ESIM_MANAGEMENT_ENABLED);
        setHasManageDevicePolicyManagedSubscriptionsPermission(false);
        setHasWriteEmbeddedPermission(true);
        doReturn(true)
                .when(mUserManager)
                .hasUserRestriction(UserManager.DISALLOW_SIM_GLOBALLY);

        callDownloadSubscription(SUBSCRIPTION, false /* switchAfterDownload */, true /* complete */,
                EuiccService.RESULT_OK, 0 /* resolvableError */, "whatever" /* callingPackage */);

        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_OK, 0 /* detailedCode */);
        assertTrue(mController.mCalledRefreshSubscriptionsAndSendResult);
    }

    @Test
    @DisableCompatChanges({EuiccManager.SHOULD_RESOLVE_PORT_INDEX_FOR_APPS})
    public void testDownloadSubscription_onlyAdminManagedAllowed_callerIsAdmin_success()
            throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_ESIM_MANAGEMENT_ENABLED);
        setHasManageDevicePolicyManagedSubscriptionsPermission(true);
        setHasWriteEmbeddedPermission(false);
        doReturn(true)
                .when(mUserManager)
                .hasUserRestriction(UserManager.DISALLOW_SIM_GLOBALLY);

        callDownloadSubscription(SUBSCRIPTION, false /* switchAfterDownload */, true /* complete */,
                EuiccService.RESULT_OK, 0 /* resolvableError */, "whatever" /* callingPackage */);

        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_OK, 0 /* detailedCode */);
        assertTrue(mController.mCalledRefreshSubscriptionsAndSendResult);
    }

    @Test
    public void testDeleteSubscription_noSuchSubscription() throws Exception {
        setHasWriteEmbeddedPermission(true);
        callDeleteSubscription(
                SUBSCRIPTION_ID, ICC_ID, false /* complete */,
                0 /* result */, "whatever" /* callingPackage */);
        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_ERROR,
                0 /* detailedCode */);
        verify(mMockConnector, never()).deleteSubscription(anyInt(), anyString(), any());
    }

    @Test
    public void testDeleteSubscription_serviceUnavailable() throws Exception {
        setHasWriteEmbeddedPermission(true);
        prepareOperationSubscription(false /* hasPrivileges */);
        callDeleteSubscription(
                SUBSCRIPTION_ID, ICC_ID, false /* complete */,
                0 /* result */, "whatever" /* callingPackage */);
        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_ERROR,
                0 /* detailedCode */);
    }

    @Test
    public void testDeleteSubscription_error() throws Exception {
        setHasWriteEmbeddedPermission(true);
        prepareOperationSubscription(false /* hasPrivileges */);
        callDeleteSubscription(
                SUBSCRIPTION_ID, ICC_ID, true /* complete */,
                42 /* result */, "whatever" /* callingPackage */);
        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_ERROR,
                42 /* detailedCode */);
    }

    @Test
    public void testDeleteSubscription_success() throws Exception {
        setHasWriteEmbeddedPermission(true);
        prepareOperationSubscription(false /* hasPrivileges */);
        callDeleteSubscription(
                SUBSCRIPTION_ID, ICC_ID, true /* complete */,
                EuiccService.RESULT_OK, "whatever" /* callingPackage */);
        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_OK, 0 /* detailedCode */);
        assertTrue(mController.mCalledRefreshSubscriptionsAndSendResult);
    }


    @Test
    public void testDeleteSubscription_adminOwned_success() throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_ESIM_MANAGEMENT_ENABLED);
        setHasWriteEmbeddedPermission(false);
        setHasManageDevicePolicyManagedSubscriptionsPermission(true);
        String callingPackage = "whatever";
        SubscriptionInfo subInfo1 = new SubscriptionInfo.Builder()
                .setId(SUBSCRIPTION_ID)
                .setEmbedded(true)
                .setIccId(ICC_ID)
                .setCardId(CARD_ID)
                .setPortIndex(TelephonyManager.DEFAULT_PORT_INDEX)
                .setGroupOwner(callingPackage)
                .build();
        ArrayList<SubscriptionInfo> subInfos = new ArrayList<>(Arrays.asList(subInfo1));
        when(mSubscriptionManager.getAvailableSubscriptionInfoList()).thenReturn(subInfos);

        callDeleteSubscription(
                SUBSCRIPTION_ID, ICC_ID, true /* complete */,
                0 /* result */, callingPackage /* callingPackage */);

        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_OK,
                0 /* detailedCode */);
    }

    @Test
    public void testDeleteSubscription_adminOwned_featureDisabled_success() throws Exception {
        mSetFlagsRule.disableFlags(Flags.FLAG_ESIM_MANAGEMENT_ENABLED);
        setHasWriteEmbeddedPermission(true);
        setHasManageDevicePolicyManagedSubscriptionsPermission(false);
        String callingPackage = "whatever";
        SubscriptionInfo subInfo1 = new SubscriptionInfo.Builder()
                .setId(SUBSCRIPTION_ID)
                .setEmbedded(true)
                .setIccId(ICC_ID)
                .setCardId(CARD_ID)
                .setPortIndex(TelephonyManager.DEFAULT_PORT_INDEX)
                .setGroupOwner(callingPackage)
                .build();
        ArrayList<SubscriptionInfo> subInfos = new ArrayList<>(Arrays.asList(subInfo1));
        when(mSubscriptionManager.getAvailableSubscriptionInfoList()).thenReturn(subInfos);

        callDeleteSubscription(
                SUBSCRIPTION_ID, ICC_ID, true /* complete */,
                0 /* result */, callingPackage /* callingPackage */);

        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_OK,
                0 /* detailedCode */);
    }

    @Test
    public void testDeleteSubscription_adminOwned_noPermissions_error() throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_ESIM_MANAGEMENT_ENABLED);
        setHasWriteEmbeddedPermission(false);
        setHasManageDevicePolicyManagedSubscriptionsPermission(false);
        String callingPackage = "whatever";
        SubscriptionInfo subInfo1 = new SubscriptionInfo.Builder()
                .setId(SUBSCRIPTION_ID)
                .setEmbedded(true)
                .setIccId(ICC_ID)
                .setCardId(CARD_ID)
                .setPortIndex(TelephonyManager.DEFAULT_PORT_INDEX)
                .setGroupOwner(callingPackage)
                .build();
        ArrayList<SubscriptionInfo> subInfos = new ArrayList<>(Arrays.asList(subInfo1));
        when(mSubscriptionManager.getAvailableSubscriptionInfoList()).thenReturn(subInfos);

        callDeleteSubscription(
                SUBSCRIPTION_ID, ICC_ID, true /* complete */,
                0 /* result */, callingPackage /* callingPackage */);

        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_ERROR,
                0 /* detailedCode */);
    }

    @Test
    public void testGetResolvedPortIndexForSubscriptionSwitchWithOutMEP() throws Exception {
        setUpUiccSlotData();
        assertEquals(TelephonyManager.DEFAULT_PORT_INDEX,
                mController.getResolvedPortIndexForSubscriptionSwitch(CARD_ID));
    }

    @Test
    public void testGetResolvedPortIndexForSubscriptionSwitchWithMEP() throws Exception {
        setUpUiccSlotDataWithMEP();
        when(mUiccSlot.getPortList()).thenReturn(new int[]{0, 1});
        when(mUiccSlot.isPortActive(TelephonyManager.DEFAULT_PORT_INDEX)).thenReturn(false);
        when(mUiccSlot.isPortActive(1)).thenReturn(true);
        when(mTelephonyManager.getActiveModemCount()).thenReturn(2);
        assertEquals(1, mController.getResolvedPortIndexForSubscriptionSwitch(CARD_ID));
    }

    @Test
    public void testGetResolvedPortIndexForSubscriptionSwitchWithUiccSlotNull() throws Exception {
        assertEquals(TelephonyManager.DEFAULT_PORT_INDEX,
                mController.getResolvedPortIndexForSubscriptionSwitch(CARD_ID));
    }

    @Test
    public void testGetResolvedPortIndexForSubscriptionSwitchWithPsimActiveAndSS()
            throws Exception {
        when(mUiccController.getUiccSlot(anyInt())).thenReturn(mUiccSlot);
        when(mUiccSlot.isRemovable()).thenReturn(true);
        when(mUiccSlot.isEuicc()).thenReturn(false);
        when(mUiccSlot.isActive()).thenReturn(true);
        when(mTelephonyManager.getActiveModemCount()).thenReturn(1);
        assertEquals(TelephonyManager.DEFAULT_PORT_INDEX,
                mController.getResolvedPortIndexForSubscriptionSwitch(CARD_ID));
    }

    @Test
    public void testGetResolvedPortIndexForSubscriptionSwitchWithPsimInActiveAndSS()
            throws Exception {
        setUpUiccSlotDataWithMEP();
        when(mUiccSlot.getPortList()).thenReturn(new int[]{0});
        when(mUiccSlot.isPortActive(TelephonyManager.DEFAULT_PORT_INDEX)).thenReturn(true);
        when(mTelephonyManager.getActiveModemCount()).thenReturn(1);
        assertEquals(TelephonyManager.DEFAULT_PORT_INDEX,
                mController.getResolvedPortIndexForSubscriptionSwitch(CARD_ID));
    }

    @Test
    public void testgetResolvedPortIndexForDisableSubscriptionForNoActiveSubscription()
            throws Exception {
        when(mSubscriptionManager.getActiveSubscriptionInfoList(anyBoolean())).thenReturn(null);
        assertEquals(-1,
                mController.getResolvedPortIndexForDisableSubscription(
                        CARD_ID, PACKAGE_NAME, true));
    }

    @Test
    public void testgetResolvedPortIndexForDisableSubscriptionForActiveSubscriptions()
            throws Exception {
        setActiveSubscriptionInfoInMEPMode();
        assertEquals(1,
                mController.getResolvedPortIndexForDisableSubscription(
                        CARD_ID, PACKAGE_NAME, false));
    }

    @Test
    public void testDeleteSubscription_noPrivileges() throws Exception {
        setHasWriteEmbeddedPermission(false);
        prepareOperationSubscription(false /* hasPrivileges */);
        callDeleteSubscription(
                SUBSCRIPTION_ID, ICC_ID, false /* complete */,
                0 /* result */, "whatever" /* callingPackage */);
        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_ERROR,
                0 /* detailedCode */);
        verify(mMockConnector, never()).deleteSubscription(anyInt(), anyString(), any());
    }

    @Test
    public void testDeleteSubscription_carrierPrivileges_success() throws Exception {
        setHasWriteEmbeddedPermission(false);
        prepareOperationSubscription(true /* hasPrivileges */);
        callDeleteSubscription(
                SUBSCRIPTION_ID, ICC_ID, true /* complete */, EuiccService.RESULT_OK, PACKAGE_NAME);
        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_OK, 0 /* detailedCode */);
        assertTrue(mController.mCalledRefreshSubscriptionsAndSendResult);
    }

    @Test
    @DisableCompatChanges({EuiccManager.SHOULD_RESOLVE_PORT_INDEX_FOR_APPS})
    public void testSwitchToSubscription_noSuchSubscription() throws Exception {
        setHasWriteEmbeddedPermission(true);
        callSwitchToSubscription(
                12345, ICC_ID, false /* complete */, 0 /* result */,
                "whatever" /* callingPackage */);
        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_ERROR,
                0 /* detailedCode */);
        verify(mMockConnector, never()).switchToSubscription(anyInt(), anyInt(), anyString(),
                anyBoolean(), any(), anyBoolean());
    }

    @Test
    @DisableCompatChanges({EuiccManager.SHOULD_RESOLVE_PORT_INDEX_FOR_APPS})
    public void testSwitchToSubscription_emptySubscription_noPrivileges() throws Exception {
        setHasWriteEmbeddedPermission(false);
        callSwitchToSubscription(
                SubscriptionManager.INVALID_SUBSCRIPTION_ID, null /* iccid */, false /* complete */,
                0 /* result */, "whatever" /* callingPackage */);
        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_ERROR,
                0 /* detailedCode */);
        verify(mMockConnector, never()).switchToSubscription(anyInt(), anyInt(), anyString(),
                anyBoolean(), any(), anyBoolean());
    }

    @Test
    @DisableCompatChanges({EuiccManager.SHOULD_RESOLVE_PORT_INDEX_FOR_APPS})
    public void testSwitchToSubscription_serviceUnavailable() throws Exception {
        setHasWriteEmbeddedPermission(true);
        prepareOperationSubscription(false /* hasPrivileges */);
        setUpUiccSlotData();
        callSwitchToSubscription(
                SUBSCRIPTION_ID, ICC_ID, false /* complete */, 0 /* result */,
                "whatever" /* callingPackage */);
        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_ERROR,
                0 /* detailedCode */);
        verify(mMockConnector).switchToSubscription(anyInt(), anyInt(), anyString(), anyBoolean(),
                any(), anyBoolean());
    }

    @Test
    @DisableCompatChanges({EuiccManager.SHOULD_RESOLVE_PORT_INDEX_FOR_APPS})
    public void testSwitchToSubscription_error() throws Exception {
        setHasWriteEmbeddedPermission(true);
        prepareOperationSubscription(false /* hasPrivileges */);
        setUpUiccSlotData();
        callSwitchToSubscription(
                SUBSCRIPTION_ID, ICC_ID, true /* complete */, 42 /* result */,
                "whatever" /* callingPackage */);
        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_ERROR,
                42 /* detailedCode */);
    }

    @Test
    @DisableCompatChanges({EuiccManager.SHOULD_RESOLVE_PORT_INDEX_FOR_APPS})
    public void testSwitchToSubscription_success() throws Exception {
        setHasWriteEmbeddedPermission(true);
        prepareOperationSubscription(false /* hasPrivileges */);
        setUpUiccSlotData();
        callSwitchToSubscription(
                SUBSCRIPTION_ID, ICC_ID, true /* complete */, EuiccService.RESULT_OK,
                "whatever" /* callingPackage */);
        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_OK, 0 /* detailedCode */);
    }

    @Test
    @DisableCompatChanges({EuiccManager.SHOULD_RESOLVE_PORT_INDEX_FOR_APPS})
    public void testSwitchToSubscription_emptySubscription_noActiveSubscription() throws Exception {
        setHasWriteEmbeddedPermission(true);
        callSwitchToSubscription(
                SubscriptionManager.INVALID_SUBSCRIPTION_ID, null /* iccid */, true /* complete */,
                EuiccService.RESULT_OK, "whatever" /* callingPackage */);
        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_ERROR,
                0 /* detailedCode */);
        verify(mMockConnector, never()).switchToSubscription(anyInt(), anyInt(), anyString(),
                anyBoolean(), any(), anyBoolean());
    }

    @Test
    @DisableCompatChanges({EuiccManager.SHOULD_RESOLVE_PORT_INDEX_FOR_APPS})
    public void testSwitchToSubscription_emptySubscription_success() throws Exception {
        setHasWriteEmbeddedPermission(true);
        setHasCarrierPrivilegesOnActiveSubscription(false);
        callSwitchToSubscription(
                SubscriptionManager.INVALID_SUBSCRIPTION_ID, null /* iccid */, true /* complete */,
                EuiccService.RESULT_OK, "whatever" /* callingPackage */);
        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_OK, 0 /* detailedCode */);
    }

    @Test
    @DisableCompatChanges({EuiccManager.SHOULD_RESOLVE_PORT_INDEX_FOR_APPS})
    public void testSwitchToSubscription_noPrivileges() throws Exception {
        setHasWriteEmbeddedPermission(false);
        prepareOperationSubscription(false /* hasPrivileges */);
        callSwitchToSubscription(
                SUBSCRIPTION_ID, ICC_ID, false /* complete */, 0 /* result */,
                "whatever" /* callingPackage */);
        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_ERROR,
                0 /* detailedCode */);
        verify(mMockConnector, never()).switchToSubscription(anyInt(), anyInt(), anyString(),
                anyBoolean(), any(), anyBoolean());
    }

    @Test
    @DisableCompatChanges({EuiccManager.SHOULD_RESOLVE_PORT_INDEX_FOR_APPS})
    public void testSwitchToSubscription_hasCarrierPrivileges() throws Exception {
        setHasWriteEmbeddedPermission(false);
        prepareOperationSubscription(true /* hasPrivileges */);
        setUpUiccSlotData();
        when(mTelephonyManager.getPhoneCount()).thenReturn(1);
        setHasCarrierPrivilegesOnActiveSubscription(true);
        callSwitchToSubscription(
                SUBSCRIPTION_ID, ICC_ID, true /* complete */, EuiccService.RESULT_OK, PACKAGE_NAME);
        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_OK, 0 /* detailedCode */);
    }

    @Test
    @DisableCompatChanges({EuiccManager.SHOULD_RESOLVE_PORT_INDEX_FOR_APPS})
    public void testSwitchToSubscription_hasCarrierPrivileges_multiSim() throws Exception {
        setHasWriteEmbeddedPermission(false);
        prepareOperationSubscription(true /* hasPrivileges */);
        setUpUiccSlotData();
        when(mTelephonyManager.getPhoneCount()).thenReturn(2);
        setCanManageSubscriptionOnTargetSim(true /* isTargetEuicc */, true /* hasPrivileges */);
        callSwitchToSubscription(
                SUBSCRIPTION_ID, ICC_ID, true /* complete */, EuiccService.RESULT_OK, PACKAGE_NAME);
        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_OK, 0 /* detailedCode */);
    }

    @Test
    @DisableCompatChanges({EuiccManager.SHOULD_RESOLVE_PORT_INDEX_FOR_APPS})
    public void testSwitchToSubscription_hasCarrierPrivileges_needsConsent() throws Exception {
        setHasWriteEmbeddedPermission(false);
        prepareOperationSubscription(true /* hasPrivileges */);
        setUpUiccSlotData();
        setHasCarrierPrivilegesOnActiveSubscription(false);
        when(mTelephonyManager.getPhoneCount()).thenReturn(1);
        callSwitchToSubscription(
                SUBSCRIPTION_ID, ICC_ID, false /* complete */, 0 /* result */, PACKAGE_NAME);
        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_RESOLVABLE_ERROR,
                0 /* detailedCode */);
        verify(mMockConnector, never()).switchToSubscription(anyInt(), anyInt(), anyString(),
                anyBoolean(), any(), anyBoolean());
        verifyResolutionIntent(EuiccService.ACTION_RESOLVE_NO_PRIVILEGES,
                EuiccOperation.ACTION_SWITCH_NO_PRIVILEGES);
    }

    @Test
    @DisableCompatChanges({EuiccManager.SHOULD_RESOLVE_PORT_INDEX_FOR_APPS})
    public void testSwitchToSubscription_hasCarrierPrivileges_needsConsent_multiSim()
            throws Exception {
        setHasWriteEmbeddedPermission(false);
        prepareOperationSubscription(true /* hasPrivileges */);
        setUpUiccSlotData();
        when(mTelephonyManager.getPhoneCount()).thenReturn(2);
        setCanManageSubscriptionOnTargetSim(true /* isTargetEuicc */, false /* hasPrivileges */);
        callSwitchToSubscription(
                SUBSCRIPTION_ID, ICC_ID, false /* complete */, 0 /* result */, PACKAGE_NAME);
        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_RESOLVABLE_ERROR,
                0 /* detailedCode */);
        verify(mMockConnector, never()).switchToSubscription(anyInt(), anyInt(), anyString(),
                anyBoolean(), any(), anyBoolean());
        verifyResolutionIntent(EuiccService.ACTION_RESOLVE_NO_PRIVILEGES,
                EuiccOperation.ACTION_SWITCH_NO_PRIVILEGES);
    }

    @Test
    @DisableCompatChanges({EuiccManager.SHOULD_RESOLVE_PORT_INDEX_FOR_APPS})
    public void testSwitchToSubscription_hasCarrierPrivileges_needsConsent_multiSim_targetPsim()
            throws Exception {
        setHasWriteEmbeddedPermission(false);
        prepareOperationSubscription(true /* hasPrivileges */);
        setUpUiccSlotData();
        when(mTelephonyManager.getPhoneCount()).thenReturn(2);
        setCanManageSubscriptionOnTargetSim(false /* isTargetEuicc */, true /* hasPrivileges */);
        callSwitchToSubscription(
                SUBSCRIPTION_ID, ICC_ID, false /* complete */, 0 /* result */, PACKAGE_NAME);
        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_RESOLVABLE_ERROR,
                0 /* detailedCode */);
        verify(mMockConnector, never()).switchToSubscription(anyInt(), anyInt(), anyString(),
                anyBoolean(), any(), anyBoolean());
        verifyResolutionIntent(EuiccService.ACTION_RESOLVE_NO_PRIVILEGES,
                EuiccOperation.ACTION_SWITCH_NO_PRIVILEGES);
    }

    @Test
    public void testUpdateSubscriptionNickname_noPrivileges() throws Exception {
        setHasWriteEmbeddedPermission(false);
        prepareOperationSubscription(false);
        callUpdateSubscriptionNickname(
                SUBSCRIPTION_ID, ICC_ID, "nickname", false /* complete */, 0 /* result */,
                PACKAGE_NAME);
        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_ERROR,
                0 /* detailedCode */);
        verify(mMockConnector, never()).updateSubscriptionNickname(anyInt(), anyString(),
                anyString(), any());
    }

    @Test
    public void testUpdateSubscriptionNickname_noSuchSubscription() throws Exception {
        setHasWriteEmbeddedPermission(true);
        callUpdateSubscriptionNickname(
                SUBSCRIPTION_ID, ICC_ID, "nickname", false /* complete */, 0 /* result */,
                PACKAGE_NAME);
        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_ERROR,
                0 /* detailedCode */);
        verify(mMockConnector, never()).updateSubscriptionNickname(anyInt(), anyString(),
                anyString(), any());
    }

    @Test
    public void testUpdateSubscriptionNickname_serviceUnavailable() throws Exception {
        setHasWriteEmbeddedPermission(true);
        prepareOperationSubscription(false /* hasPrivileges */);
        callUpdateSubscriptionNickname(
                SUBSCRIPTION_ID, ICC_ID, "nickname", false /* complete */, 0 /* result */,
                PACKAGE_NAME);
        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_ERROR,
                0 /* detailedCode */);
        verify(mMockConnector).updateSubscriptionNickname(anyInt(), anyString(), anyString(),
                any());
    }

    @Test
    public void testUpdateSubscriptionNickname_error() throws Exception {
        setHasWriteEmbeddedPermission(true);
        prepareOperationSubscription(false /* hasPrivileges */);
        callUpdateSubscriptionNickname(
                SUBSCRIPTION_ID, ICC_ID, "nickname", true /* complete */, 42 /* result */,
                PACKAGE_NAME);
        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_ERROR,
                42 /* detailedCode */);
    }

    @Test
    public void testUpdateSubscriptionNickname_success() throws Exception {
        setHasWriteEmbeddedPermission(true);
        prepareOperationSubscription(false /* hasPrivileges */);
        callUpdateSubscriptionNickname(
                SUBSCRIPTION_ID, ICC_ID, "nickname", true /* complete */, EuiccService.RESULT_OK,
                PACKAGE_NAME);
        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_OK, 0 /* detailedCode */);
    }

    @Test(expected = SecurityException.class)
    public void testEraseSubscriptions_noPrivileges() throws Exception {
        setHasWriteEmbeddedPermission(false);
        callEraseSubscriptions(false /* complete */, 0 /* result */);
    }

    @Test
    public void testEraseSubscriptions_serviceUnavailable() throws Exception {
        setHasWriteEmbeddedPermission(true);
        callEraseSubscriptions(false /* complete */, 0 /* result */);
        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_ERROR,
                0 /* detailedCode */);
        verify(mMockConnector).eraseSubscriptions(anyInt(), any());
    }

    @Test
    public void testEraseSubscriptions_error() throws Exception {
        setHasWriteEmbeddedPermission(true);
        callEraseSubscriptions(true /* complete */, 42 /* result */);
        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_ERROR, 42 /* detailedCode */);
    }

    @Test
    public void testEraseSubscriptions_success() throws Exception {
        setHasWriteEmbeddedPermission(true);
        callEraseSubscriptions(true /* complete */, EuiccService.RESULT_OK);
        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_OK, 0 /* detailedCode */);
        assertTrue(mController.mCalledRefreshSubscriptionsAndSendResult);
    }

    @Test(expected = SecurityException.class)
    public void testEraseSubscriptionsWithOptions_noPrivileges() throws Exception {
        setHasWriteEmbeddedPermission(false);
        callEraseSubscriptionsWithOptions(false /* complete */, 0 /* result */);
    }

    @Test
    public void testEraseSubscriptionsWithOptions_serviceUnavailable() throws Exception {
        setHasWriteEmbeddedPermission(true);
        callEraseSubscriptionsWithOptions(false /* complete */, 0 /* result */);
        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_ERROR,
                0 /* detailedCode */);
        verify(mMockConnector).eraseSubscriptionsWithOptions(anyInt(), anyInt(), any());
    }

    @Test
    public void testEraseSubscriptionsWithOptions_error() throws Exception {
        setHasWriteEmbeddedPermission(true);
        callEraseSubscriptionsWithOptions(true /* complete */, 42 /* result */);
        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_ERROR, 42 /* detailedCode */);
    }

    @Test
    public void testEraseSubscriptionsWithOptions_success() throws Exception {
        setHasWriteEmbeddedPermission(true);
        callEraseSubscriptionsWithOptions(true /* complete */, EuiccService.RESULT_OK);
        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_OK, 0 /* detailedCode */);
        assertTrue(mController.mCalledRefreshSubscriptionsAndSendResult);
    }

    @Test(expected = SecurityException.class)
    public void testRetainSubscriptionsForFactoryReset_noPrivileges() throws Exception {
        setHasMasterClearPermission(false);
        callRetainSubscriptionsForFactoryReset(false /* complete */, 0 /* result */);
    }

    @Test
    public void testRetainSubscriptionsForFactoryReset_serviceUnavailable() throws Exception {
        setHasMasterClearPermission(true);
        callRetainSubscriptionsForFactoryReset(false /* complete */, 0 /* result */);
        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_ERROR, 0 /* detailedCode */);
        verify(mMockConnector).retainSubscriptions(anyInt(), any());
    }

    @Test
    public void testRetainSubscriptionsForFactoryReset_error() throws Exception {
        setHasMasterClearPermission(true);
        callRetainSubscriptionsForFactoryReset(true /* complete */, 42 /* result */);
        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_ERROR, 42 /* detailedCode */);
    }

    @Test
    public void testRetainSubscriptionsForFactoryReset_success() throws Exception {
        setHasMasterClearPermission(true);
        callRetainSubscriptionsForFactoryReset(true /* complete */, EuiccService.RESULT_OK);
        verifyIntentSent(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_OK, 0 /* detailedCode */);
    }

    @Test
    public void testAddExtrasToResultIntent_withSmdxOperationCode_normal_case() {
        // Same setup as testGetDownloadableSubscriptionMetadata_error
        setHasWriteEmbeddedPermission(true);
        GetDownloadableSubscriptionMetadataResult result =
                new GetDownloadableSubscriptionMetadataResult(0xA8b1051 /* result */,
                        null /* subscription */);
        callGetDownloadableSubscriptionMetadata(SUBSCRIPTION, true /* complete */, result);

        assertEquals(mController.mExtrasIntent.getIntExtra(
                EuiccManager.EXTRA_EMBEDDED_SUBSCRIPTION_OPERATION_CODE, -1),
                EuiccManager.OPERATION_SMDX_SUBJECT_REASON_CODE);
        assertEquals(mController.mExtrasIntent.getStringExtra(
                EuiccManager.EXTRA_EMBEDDED_SUBSCRIPTION_SMDX_SUBJECT_CODE), "8.11.1");
        assertEquals(mController.mExtrasIntent.getStringExtra(
                EuiccManager.EXTRA_EMBEDDED_SUBSCRIPTION_SMDX_REASON_CODE), "5.1");

    }

    @Test
    public void testAddExtrasToResultIntent_withSmdxOperationCode_general_case() {
        // Same setup as testGetDownloadableSubscriptionMetadata_error
        setHasWriteEmbeddedPermission(true);
        GetDownloadableSubscriptionMetadataResult result =
                new GetDownloadableSubscriptionMetadataResult(0xA123456 /* result */,
                        null /* subscription */);
        callGetDownloadableSubscriptionMetadata(SUBSCRIPTION, true /* complete */, result);

        assertEquals(mController.mExtrasIntent.getIntExtra(
                EuiccManager.EXTRA_EMBEDDED_SUBSCRIPTION_OPERATION_CODE, -1),
                EuiccManager.OPERATION_SMDX_SUBJECT_REASON_CODE);
        assertEquals(mController.mExtrasIntent.getStringExtra(
                EuiccManager.EXTRA_EMBEDDED_SUBSCRIPTION_SMDX_SUBJECT_CODE), "1.2.3");
        assertEquals(mController.mExtrasIntent.getStringExtra(
                EuiccManager.EXTRA_EMBEDDED_SUBSCRIPTION_SMDX_REASON_CODE), "4.5.6");

    }

    @Test
    public void testAddExtrasToResultIntent_withSmdxOperationCode_and_padding() {
        // Same setup as testGetDownloadableSubscriptionMetadata_error
        setHasWriteEmbeddedPermission(true);
        GetDownloadableSubscriptionMetadataResult result =
                new GetDownloadableSubscriptionMetadataResult(0xA003006 /* result */,
                        null /* subscription */);
        callGetDownloadableSubscriptionMetadata(SUBSCRIPTION, true /* complete */, result);

        assertEquals(mController.mExtrasIntent.getIntExtra(
                EuiccManager.EXTRA_EMBEDDED_SUBSCRIPTION_OPERATION_CODE, -1),
                EuiccManager.OPERATION_SMDX_SUBJECT_REASON_CODE);
        assertEquals(mController.mExtrasIntent.getStringExtra(
                EuiccManager.EXTRA_EMBEDDED_SUBSCRIPTION_SMDX_SUBJECT_CODE), "3");
        assertEquals(mController.mExtrasIntent.getStringExtra(
                EuiccManager.EXTRA_EMBEDDED_SUBSCRIPTION_SMDX_REASON_CODE), "6");
    }

    @Test
    public void testAddExtrasToResultIntent_withOperationCode() {
        // Same setup as testGetDownloadableSubscriptionMetadata_error
        setHasWriteEmbeddedPermission(true);
        GetDownloadableSubscriptionMetadataResult result =
                new GetDownloadableSubscriptionMetadataResult(0x12345678 /* result */,
                        null /* subscription */);
        callGetDownloadableSubscriptionMetadata(SUBSCRIPTION, true /* complete */, result);

        assertEquals(mController.mExtrasIntent.getIntExtra(
                EuiccManager.EXTRA_EMBEDDED_SUBSCRIPTION_OPERATION_CODE, -1),
                0x12);
        assertEquals(mController.mExtrasIntent.getIntExtra(
                EuiccManager.EXTRA_EMBEDDED_SUBSCRIPTION_ERROR_CODE, -1), 0x345678);
    }

    @Test
    @DisableCompatChanges({SWITCH_WITHOUT_PORT_INDEX_EXCEPTION_ON_DISABLE})
    public void testIsCompactChangeEnabled_disable() {
        assertFalse(mController.isCompatChangeEnabled(TEST_PACKAGE_NAME,
                SWITCH_WITHOUT_PORT_INDEX_EXCEPTION_ON_DISABLE));
    }

    @Test
    @EnableCompatChanges({SWITCH_WITHOUT_PORT_INDEX_EXCEPTION_ON_DISABLE})
    public void testIsCompactChangeEnabled_enable() {
        assertTrue(mController.isCompatChangeEnabled(TEST_PACKAGE_NAME,
                SWITCH_WITHOUT_PORT_INDEX_EXCEPTION_ON_DISABLE));
    }

    @Test
    @EnableCompatChanges({EuiccManager.INACTIVE_PORT_AVAILABILITY_CHECK,
            TelephonyManager.ENABLE_FEATURE_MAPPING})
    public void testIsSimPortAvailable_WithTelephonyFeatureMapping() throws Exception {
        // Replace field to set SDK version of vendor partition to Android V
        int vendorApiLevel = Build.VERSION_CODES.VANILLA_ICE_CREAM;
        replaceInstance(EuiccController.class, "mVendorApiLevel", (EuiccController) mController,
                vendorApiLevel);

        // Feature flag enabled, device has required telephony feature.
        doReturn(true).when(mFeatureFlags).enforceTelephonyFeatureMappingForPublicApis();
        doReturn(true).when(mPackageManager).hasSystemFeature(
                eq(PackageManager.FEATURE_TELEPHONY_EUICC));

        setUiccCardInfos(false, true, true);

        // assert non euicc card id
        assertFalse(mController.isSimPortAvailable(REMOVABLE_CARD_ID, 0, TEST_PACKAGE_NAME));

        // assert invalid port index
        assertFalse(mController.isSimPortAvailable(CARD_ID, 5 /* portIndex */, TEST_PACKAGE_NAME));

        // Device does not have required telephony feature.
        doReturn(false).when(mPackageManager).hasSystemFeature(
                eq(PackageManager.FEATURE_TELEPHONY_EUICC));
        assertThrows(UnsupportedOperationException.class,
                () -> mController.isSimPortAvailable(REMOVABLE_CARD_ID, 0, TEST_PACKAGE_NAME));
    }

    @Test
    @EnableCompatChanges({EuiccManager.INACTIVE_PORT_AVAILABILITY_CHECK})
    public void testIsSimPortAvailable_invalidCase() {
        setUiccCardInfos(false, true, true);
        // assert non euicc card id
        assertFalse(mController.isSimPortAvailable(REMOVABLE_CARD_ID, 0, TEST_PACKAGE_NAME));

        // assert invalid port index
        assertFalse(mController.isSimPortAvailable(CARD_ID, 5 /* portIndex */, TEST_PACKAGE_NAME));
    }

    @Test
    @EnableCompatChanges({EuiccManager.INACTIVE_PORT_AVAILABILITY_CHECK})
    public void testIsSimPortAvailable_port_active() throws Exception {
        setUiccCardInfos(false, true, true);

        // port has empty iccid
        assertTrue(mController.isSimPortAvailable(CARD_ID, 0, TEST_PACKAGE_NAME));

        // Set port is active, has valid iccid(may be boot profile) and UiccProfile is empty
        setUiccCardInfos(false, true, false);
        when(mUiccController.getUiccPortForSlot(anyInt(), anyInt())).thenReturn(mUiccPort);
        when(mUiccPort.getUiccProfile()).thenReturn(mUiccProfile);
        when(mUiccProfile.isEmptyProfile()).thenReturn(true);
        assertTrue(mController.isSimPortAvailable(CARD_ID, 0, TEST_PACKAGE_NAME));

        // port is active, valid iccid, not empty profile but Phone object is null
        when(mUiccPort.getUiccProfile()).thenReturn(mUiccProfile);
        when(mUiccProfile.isEmptyProfile()).thenReturn(false);
        replaceInstance(PhoneFactory.class, "sPhones", null, new Phone[] {mPhone});
        // logicalSlotIndex of port#0 is 1, Phone object should be null
        assertFalse(mController.isSimPortAvailable(CARD_ID, 0, TEST_PACKAGE_NAME));

        // port is active, valid iccid, not empty profile but no carrier privileges
        when(mUiccPort.getUiccProfile()).thenReturn(mUiccProfile);
        when(mUiccProfile.isEmptyProfile()).thenReturn(false);
        replaceInstance(PhoneFactory.class, "sPhones", null, new Phone[] {mPhone, mPhone});
        when(mPhone.getCarrierPrivilegesTracker()).thenReturn(null);
        assertFalse(mController.isSimPortAvailable(CARD_ID, 0, TEST_PACKAGE_NAME));
        when(mPhone.getCarrierPrivilegesTracker()).thenReturn(mCarrierPrivilegesTracker);
        when(mCarrierPrivilegesTracker.getCarrierPrivilegeStatusForPackage(TEST_PACKAGE_NAME))
                .thenReturn(TelephonyManager.CARRIER_PRIVILEGE_STATUS_NO_ACCESS);
        assertFalse(mController.isSimPortAvailable(CARD_ID, 0, TEST_PACKAGE_NAME));

        // port is active, valid iccid, not empty profile and has carrier privileges
        when(mCarrierPrivilegesTracker.getCarrierPrivilegeStatusForPackage(TEST_PACKAGE_NAME))
                .thenReturn(TelephonyManager.CARRIER_PRIVILEGE_STATUS_HAS_ACCESS);
        assertTrue(mController.isSimPortAvailable(CARD_ID, 0, TEST_PACKAGE_NAME));
    }

    @Test
    @EnableCompatChanges({EuiccManager.INACTIVE_PORT_AVAILABILITY_CHECK})
    public void testIsSimPortAvailable_port_inActive() {
        setUiccCardInfos(false, false, true);
        when(mUiccController.getUiccSlots()).thenReturn(new UiccSlot[]{mUiccSlot});
        when(mUiccSlot.isRemovable()).thenReturn(true);

        // Check getRemovableNonEuiccSlot null case
        when(mUiccSlot.isEuicc()).thenReturn(true);
        assertFalse(mController.isSimPortAvailable(CARD_ID, 0, TEST_PACKAGE_NAME));

        // Check getRemovableNonEuiccSlot isActive() false case
        when(mUiccSlot.isEuicc()).thenReturn(false);
        when(mUiccSlot.isActive()).thenReturn(false);
        assertFalse(mController.isSimPortAvailable(CARD_ID, 0, TEST_PACKAGE_NAME));

        // assert false,multisim is not enabled
        when(mUiccSlot.isEuicc()).thenReturn(false);
        when(mUiccSlot.isActive()).thenReturn(true);
        when(mTelephonyManager.checkCarrierPrivilegesForPackageAnyPhone(TEST_PACKAGE_NAME))
                .thenReturn(TelephonyManager.CARRIER_PRIVILEGE_STATUS_HAS_ACCESS);
        when(mTelephonyManager.isMultiSimEnabled()).thenReturn(false);
        assertFalse(mController.isSimPortAvailable(CARD_ID, 0, TEST_PACKAGE_NAME));

        // assert false, caller does not have carrier privileges
        setHasWriteEmbeddedPermission(false);
        when(mTelephonyManager.isMultiSimEnabled()).thenReturn(true);
        when(mUiccSlot.getPortList()).thenReturn(new int[] {0});
        when(mSubscriptionManager.getActiveSubscriptionInfoForSimSlotIndex(anyInt())).thenReturn(
                new SubscriptionInfo.Builder().build());
        when(mTelephonyManager.checkCarrierPrivilegesForPackageAnyPhone(TEST_PACKAGE_NAME))
                .thenReturn(TelephonyManager.CARRIER_PRIVILEGE_STATUS_NO_ACCESS);
        assertFalse(mController.isSimPortAvailable(CARD_ID, 0, TEST_PACKAGE_NAME));

        // assert true, caller does not have carrier privileges but has write_embedded permission
        setHasWriteEmbeddedPermission(true);
        assertTrue(mController.isSimPortAvailable(CARD_ID, 0, TEST_PACKAGE_NAME));

        // assert true, caller has carrier privileges
        setHasWriteEmbeddedPermission(false);
        when(mTelephonyManager.checkCarrierPrivilegesForPackageAnyPhone(TEST_PACKAGE_NAME))
                .thenReturn(TelephonyManager.CARRIER_PRIVILEGE_STATUS_HAS_ACCESS);
        assertTrue(mController.isSimPortAvailable(CARD_ID, 0, TEST_PACKAGE_NAME));
    }

    @Test
    @DisableCompatChanges({EuiccManager.INACTIVE_PORT_AVAILABILITY_CHECK})
    public void testIsSimPortAvailable_port_inActive_disable_compactChange() {
        setUiccCardInfos(false, false, true);
        when(mUiccController.getUiccSlots()).thenReturn(new UiccSlot[]{mUiccSlot});
        when(mUiccSlot.isRemovable()).thenReturn(true);
        when(mUiccSlot.isEuicc()).thenReturn(false);
        when(mUiccSlot.isActive()).thenReturn(true);

        when(mTelephonyManager.isMultiSimEnabled()).thenReturn(true);
        when(mUiccSlot.getPortList()).thenReturn(new int[] {0});
        when(mSubscriptionManager.getActiveSubscriptionInfoForSimSlotIndex(anyInt())).thenReturn(
                new SubscriptionInfo.Builder().build());
        when(mTelephonyManager.checkCarrierPrivilegesForPackageAnyPhone(TEST_PACKAGE_NAME))
                .thenReturn(TelephonyManager.CARRIER_PRIVILEGE_STATUS_HAS_ACCESS);
        setHasWriteEmbeddedPermission(true);

        // Even though all conditions are true, isSimPortAvailable should return false as
        // compact change is disabled
        assertFalse(mController.isSimPortAvailable(CARD_ID, 0, TEST_PACKAGE_NAME));
    }


    private void setUiccCardInfos(boolean isMepSupported, boolean isPortActive,
            boolean isEmptyPort) {
        List<UiccPortInfo> euiccPortInfoList;
        if (isMepSupported) {
            euiccPortInfoList = Arrays.asList(
                    new UiccPortInfo(isEmptyPort ? "" : ICC_ID /* iccId */, 0 /* portIdx */,
                            isPortActive ? 1 : -1 /* logicalSlotIdx */,
                            isPortActive /* isActive */),
                    new UiccPortInfo(isEmptyPort ? "" : ICC_ID /* iccId */, 1 /* portIdx */,
                            -1 /* logicalSlotIdx */,
                            isPortActive /* isActive */));
        } else {
            euiccPortInfoList = Collections.singletonList(
                    new UiccPortInfo(isEmptyPort ? "" : ICC_ID /* iccId */, 0 /* portIdx */,
                            isPortActive ? 1 : -1 /* logicalSlotIdx */,
                            isPortActive /* isActive */)
                    );
        }

        UiccCardInfo cardInfo1 = new UiccCardInfo(true, CARD_ID, "", 0,
                false /* isRemovable */,
                isMepSupported /* isMultipleEnabledProfileSupported */,
                euiccPortInfoList);
        UiccCardInfo cardInfo2 = new UiccCardInfo(false /* isEuicc */,
                REMOVABLE_CARD_ID /* cardId */,
                "", 0, true /* isRemovable */,
                false /* isMultipleEnabledProfileSupported */,
                Collections.singletonList(
                        new UiccPortInfo("" /* iccId */, 0 /* portIdx */,
                                0 /* logicalSlotIdx */, true /* isActive */)));
        ArrayList<UiccCardInfo> cardInfos = new ArrayList<>();
        cardInfos.add(cardInfo1);
        cardInfos.add(cardInfo2);
        when(mTelephonyManager.getUiccCardsInfo()).thenReturn(cardInfos);
    }

    private void setUpUiccSlotData() {
        when(mUiccController.getUiccSlot(anyInt())).thenReturn(mUiccSlot);
        // TODO(b/199559633): Add test cases for isMultipleEnabledProfileSupported true case
        when(mUiccSlot.isMultipleEnabledProfileSupported()).thenReturn(false);
    }

    private void setUpUiccSlotDataWithMEP() {
        when(mUiccController.getUiccSlot(anyInt())).thenReturn(mUiccSlot);
        when(mUiccSlot.isMultipleEnabledProfileSupported()).thenReturn(true);
    }

    private void setGetEidPermissions(
            boolean hasPhoneStatePrivileged, boolean hasCarrierPrivileges) throws Exception {
        doReturn(hasPhoneStatePrivileged
                ? PackageManager.PERMISSION_GRANTED : PackageManager.PERMISSION_DENIED)
                .when(mContext)
                .checkCallingOrSelfPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE);
        when(mTelephonyManager.getPhoneCount()).thenReturn(1);
        setHasCarrierPrivilegesOnActiveSubscription(hasCarrierPrivileges);
    }

    private void setGetAvailableMemoryInBytesPermissions(
            boolean hasPhoneState, boolean hasPhoneStatePrivileged, boolean hasCarrierPrivileges)
            throws Exception {
        doReturn(
                        hasPhoneState
                                ? PackageManager.PERMISSION_GRANTED
                                : PackageManager.PERMISSION_DENIED)
                .when(mContext)
                .checkCallingOrSelfPermission(Manifest.permission.READ_PHONE_STATE);
        doReturn(
                        hasPhoneStatePrivileged
                                ? PackageManager.PERMISSION_GRANTED
                                : PackageManager.PERMISSION_DENIED)
                .when(mContext)
                .checkCallingOrSelfPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE);
        when(mTelephonyManager.getPhoneCount()).thenReturn(1);
        setHasCarrierPrivilegesOnActiveSubscription(hasCarrierPrivileges);
    }

    private void setHasWriteEmbeddedPermission(boolean hasPermission) {
        doReturn(hasPermission
                ? PackageManager.PERMISSION_GRANTED : PackageManager.PERMISSION_DENIED)
                .when(mContext)
                .checkCallingOrSelfPermission(Manifest.permission.WRITE_EMBEDDED_SUBSCRIPTIONS);
    }

    private void setHasManageDevicePolicyManagedSubscriptionsPermission(boolean hasPermission) {
        doReturn(hasPermission
                ? PackageManager.PERMISSION_GRANTED : PackageManager.PERMISSION_DENIED)
                .when(mContext)
                .checkCallingOrSelfPermission(
                        Manifest.permission.MANAGE_DEVICE_POLICY_MANAGED_SUBSCRIPTIONS);
    }


    private void setHasMasterClearPermission(boolean hasPermission) {
        Stubber stubber = hasPermission ? doNothing() : doThrow(new SecurityException());
        stubber.when(mContext).enforceCallingPermission(
                eq(Manifest.permission.MASTER_CLEAR), anyString());
    }

    private void setHasCarrierPrivilegesOnActiveSubscription(boolean hasPrivileges)
            throws Exception {
        SubscriptionInfo.Builder builder = new SubscriptionInfo.Builder()
                .setSimSlotIndex(0)
                .setPortIndex(mTelephonyManager.DEFAULT_PORT_INDEX)
                .setDisplayNameSource(SubscriptionManager.NAME_SOURCE_CARRIER_ID)
                .setEmbedded(true);
        if (hasPrivileges) {
            builder.setNativeAccessRules(new UiccAccessRule[] { ACCESS_RULE });
        }
        builder.setCardId(CARD_ID);
        SubscriptionInfo subInfo = builder.build();

        when(mSubscriptionManager.canManageSubscription(subInfo, PACKAGE_NAME)).thenReturn(
                hasPrivileges);
        when(mSubscriptionManager.getActiveSubscriptionInfoList(anyBoolean())).thenReturn(
                Collections.singletonList(subInfo));
    }

    private void setCanManageSubscriptionOnTargetSim(boolean isTargetEuicc, boolean hasPrivileges)
            throws Exception {
        UiccCardInfo cardInfo1 = new UiccCardInfo(isTargetEuicc, CARD_ID, "", 0,
                false /* isRemovable */,
                false /* isMultipleEnabledProfileSupported */,
                Collections.singletonList(
                        new UiccPortInfo("" /* iccId */, 0 /* portIdx */,
                                -1 /* logicalSlotIdx */, false /* isActive */)));
        UiccCardInfo cardInfo2 = new UiccCardInfo(true /* isEuicc */, 1 /* cardId */,
                "", 0, false /* isRemovable */,
                false /* isMultipleEnabledProfileSupported */,
                Collections.singletonList(
                        new UiccPortInfo("" /* iccId */, 0 /* portIdx */,
                                -1 /* logicalSlotIdx */, false /* isActive */)));
        ArrayList<UiccCardInfo> cardInfos = new ArrayList<>();
        cardInfos.add(cardInfo1);
        cardInfos.add(cardInfo2);
        when(mTelephonyManager.getUiccCardsInfo()).thenReturn(cardInfos);

        SubscriptionInfo subInfo1 = new SubscriptionInfo.Builder()
                .setNativeAccessRules(hasPrivileges ? new UiccAccessRule[] { ACCESS_RULE } : null)
                .setEmbedded(true)
                .setCardId(CARD_ID)
                .setPortIndex(mTelephonyManager.DEFAULT_PORT_INDEX)
                .build();
        SubscriptionInfo subInfo2 = new SubscriptionInfo.Builder()
                .setNativeAccessRules(hasPrivileges ? new UiccAccessRule[] { ACCESS_RULE } : null)
                .setEmbedded(true)
                .setCardId(2)
                .setPortIndex(TelephonyManager.DEFAULT_PORT_INDEX)
                .build();
        when(mSubscriptionManager.canManageSubscription(subInfo1, PACKAGE_NAME)).thenReturn(
                hasPrivileges);
        when(mSubscriptionManager.canManageSubscription(subInfo2, PACKAGE_NAME)).thenReturn(
                hasPrivileges);
        ArrayList<SubscriptionInfo> subInfos = new ArrayList<>(Arrays.asList(subInfo1, subInfo2));
        when(mSubscriptionManager.getActiveSubscriptionInfoList(anyBoolean())).thenReturn(subInfos);
    }

    private void setActiveSubscriptionInfoInMEPMode()
            throws Exception {
        SubscriptionInfo subInfo1 = new SubscriptionInfo.Builder()
                .setEmbedded(true)
                .setCardId(CARD_ID)
                .setPortIndex(TelephonyManager.DEFAULT_PORT_INDEX)
                .build();
        SubscriptionInfo subInfo2 = new SubscriptionInfo.Builder()
                .setEmbedded(true)
                .setCardId(CARD_ID)
                .setPortIndex(1)
                .build();
        when(mSubscriptionManager.canManageSubscription(subInfo1, PACKAGE_NAME)).thenReturn(
                false);
        when(mSubscriptionManager.canManageSubscription(subInfo2, PACKAGE_NAME)).thenReturn(
                true);
        ArrayList<SubscriptionInfo> subInfos = new ArrayList<>(Arrays.asList(subInfo1, subInfo2));
        when(mSubscriptionManager.getActiveSubscriptionInfoList(anyBoolean())).thenReturn(subInfos);
    }

    private void prepareOperationSubscription(boolean hasPrivileges) throws Exception {
        SubscriptionInfo subInfo = new SubscriptionInfo.Builder()
                .setId(SUBSCRIPTION_ID)
                .setIccId(ICC_ID)
                .setNativeAccessRules(hasPrivileges ? new UiccAccessRule[] { ACCESS_RULE } : null)
                .setEmbedded(true)
                .setCardId(CARD_ID)
                .build();
        when(mSubscriptionManager.canManageSubscription(subInfo, PACKAGE_NAME)).thenReturn(
                hasPrivileges);
        when(mSubscriptionManager.getAvailableSubscriptionInfoList()).thenReturn(
                Collections.singletonList(subInfo));
    }

    private String callGetEid(final boolean success, final @Nullable String eid, int cardId) {
        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Exception {
                EuiccConnector.GetEidCommandCallback cb = invocation
                        .getArgument(1 /* resultCallback */);
                if (success) {
                    cb.onGetEidComplete(eid);
                } else {
                    cb.onEuiccServiceUnavailable();
                }
                return null;
            }
        }).when(mMockConnector).getEid(anyInt(),
                Mockito.<EuiccConnector.GetEidCommandCallback>any());
        return mController.getEid(cardId, PACKAGE_NAME);
    }

    private long callGetAvailableMemoryInBytes(
            final AvailableMemoryCallbackStatus status,
            final long availableMemoryInBytes,
            int cardId) {
        doAnswer(
                new Answer<Void>() {
                    @Override
                    public Void answer(InvocationOnMock invocation) throws Exception {
                        EuiccConnector.GetAvailableMemoryInBytesCommandCallback cb =
                                invocation.getArgument(1 /* resultCallback */);
                        if (status == AvailableMemoryCallbackStatus.SUCCESS) {
                            cb.onGetAvailableMemoryInBytesComplete(availableMemoryInBytes);
                        } else if (status == AvailableMemoryCallbackStatus.UNAVAILABLE) {
                            cb.onEuiccServiceUnavailable();
                        } else if (status == AvailableMemoryCallbackStatus.EXCEPTION) {
                            cb.onUnsupportedOperationExceptionComplete("exception message");
                        }
                        return null;
                    }
                })
                .when(mMockConnector)
                .getAvailableMemoryInBytes(
                        anyInt(),
                        Mockito.<EuiccConnector.GetAvailableMemoryInBytesCommandCallback>any());
        return mController.getAvailableMemoryInBytes(cardId, PACKAGE_NAME);
    }

    private int callGetOtaStatus(final boolean success, final int status) {
        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Exception {
                GetOtaStatusCommandCallback cb = invocation.getArgument(1 /* resultCallback */);
                if (success) {
                    cb.onGetOtaStatusComplete(status);
                } else {
                    cb.onEuiccServiceUnavailable();
                }
                return null;
            }
        }).when(mMockConnector).getOtaStatus(anyInt(), Mockito.<GetOtaStatusCommandCallback>any());
        return mController.getOtaStatus(CARD_ID);
    }

    private void callStartOtaUpdatingIfNecessary(
            final boolean serviceAvailable, int status) {
        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Exception {
                OtaStatusChangedCallback cb = invocation.getArgument(1 /* resultCallback */);
                if (!serviceAvailable) {
                    cb.onEuiccServiceUnavailable();
                } else {
                    cb.onOtaStatusChanged(status);
                }
                return null;
            }
        }).when(mMockConnector).startOtaIfNecessary(anyInt(),
                Mockito.<OtaStatusChangedCallback>any());

        mController.startOtaUpdatingIfNecessary();
    }

    private EuiccInfo callGetEuiccInfo(final boolean success, final @Nullable EuiccInfo euiccInfo) {
        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Exception {
                EuiccConnector.GetEuiccInfoCommandCallback cb = invocation
                        .getArgument(1 /* resultCallback */);
                if (success) {
                    cb.onGetEuiccInfoComplete(euiccInfo);
                } else {
                    cb.onEuiccServiceUnavailable();
                }
                return null;
            }
        }).when(mMockConnector).getEuiccInfo(anyInt(), any());
        return mController.getEuiccInfo(CARD_ID);
    }

    private void prepareGetDownloadableSubscriptionMetadataCall(
            final boolean complete, final GetDownloadableSubscriptionMetadataResult result) {
        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Exception {
                EuiccConnector.GetMetadataCommandCallback cb = invocation
                        .getArgument(5 /* resultCallback */);
                if (complete) {
                    cb.onGetMetadataComplete(CARD_ID, result);
                } else {
                    cb.onEuiccServiceUnavailable();
                }
                return null;
            }
        }).when(mMockConnector).getDownloadableSubscriptionMetadata(anyInt(), anyInt(), any(),
                anyBoolean(), anyBoolean(), any());
    }

    private void callGetDownloadableSubscriptionMetadata(DownloadableSubscription subscription,
            boolean complete, GetDownloadableSubscriptionMetadataResult result) {
        prepareGetDownloadableSubscriptionMetadataCall(complete, result);
        PendingIntent resultCallback = PendingIntent.getBroadcast(mContext, 0, new Intent(),
                PendingIntent.FLAG_IMMUTABLE);
        mController.getDownloadableSubscriptionMetadata(0, subscription, PACKAGE_NAME,
                resultCallback);
    }

    private void callGetDefaultDownloadableSubscriptionList(
            boolean complete, GetDefaultDownloadableSubscriptionListResult result) {
        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Exception {
                EuiccConnector.GetDefaultListCommandCallback cb = invocation
                        .getArgument(2 /* resultCallBack */);
                if (complete) {
                    cb.onGetDefaultListComplete(CARD_ID, result);
                } else {
                    cb.onEuiccServiceUnavailable();
                }
                return null;
            }
        }).when(mMockConnector).getDefaultDownloadableSubscriptionList(anyInt(), anyBoolean(),
                any());
        PendingIntent resultCallback = PendingIntent.getBroadcast(mContext, 0, new Intent(),
                PendingIntent.FLAG_IMMUTABLE);
        mController.getDefaultDownloadableSubscriptionList(CARD_ID, PACKAGE_NAME, resultCallback);
    }

    private void callDownloadSubscription(DownloadableSubscription subscription,
            boolean switchAfterDownload, final boolean complete, final int result,
            final int resolvableError, String callingPackage) {
        PendingIntent resultCallback = PendingIntent.getBroadcast(mContext, 0, new Intent(),
                PendingIntent.FLAG_IMMUTABLE);
        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Exception {
                EuiccConnector.DownloadCommandCallback cb = invocation
                        .getArgument(6 /* resultCallback */);
                if (complete) {
                    DownloadSubscriptionResult downloadRes = new DownloadSubscriptionResult(
                            result, resolvableError, -1 /* cardId */);
                    cb.onDownloadComplete(downloadRes);
                } else {
                    cb.onEuiccServiceUnavailable();
                }
                return null;
            }
        }).when(mMockConnector).downloadSubscription(anyInt(), anyInt(),
                any(), eq(switchAfterDownload), anyBoolean(), any(), any());
        mController.downloadSubscription(CARD_ID, subscription, switchAfterDownload, callingPackage,
                null /* resolvedBundle */, resultCallback);
        // EUICC_PROVISIONED setting should match whether the download was successful.
        assertEquals(complete && result == EuiccService.RESULT_OK ? 1 : 0,
                Settings.Global.getInt(mContext.getContentResolver(),
                        Settings.Global.EUICC_PROVISIONED, 0));
    }

    private void callDeleteSubscription(int subscriptionId, String iccid, final boolean complete,
            final int result, String callingPackage) {
        PendingIntent resultCallback = PendingIntent.getBroadcast(mContext, 0, new Intent(),
                PendingIntent.FLAG_IMMUTABLE);
        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Exception {
                EuiccConnector.DeleteCommandCallback cb = invocation
                        .getArgument(2 /* resultCallback */);
                if (complete) {
                    cb.onDeleteComplete(result);
                } else {
                    cb.onEuiccServiceUnavailable();
                }
                return null;
            }
        }).when(mMockConnector).deleteSubscription(anyInt(), eq(iccid), any());
        mController.deleteSubscription(CARD_ID, subscriptionId, callingPackage, resultCallback);
    }

    private void callSwitchToSubscription(int subscriptionId, String iccid, final boolean complete,
            final int result, String callingPackage) {
        PendingIntent resultCallback = PendingIntent.getBroadcast(mContext, 0, new Intent(),
                PendingIntent.FLAG_IMMUTABLE);
        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Exception {
                EuiccConnector.SwitchCommandCallback cb = invocation
                        .getArgument(4 /* resultCallback */);
                if (complete) {
                    cb.onSwitchComplete(result);
                } else {
                    cb.onEuiccServiceUnavailable();
                }
                return null;
            }
        }).when(mMockConnector).switchToSubscription(anyInt(), anyInt(), eq(iccid), anyBoolean(),
                any(), anyBoolean());
        mController.switchToSubscription(CARD_ID, subscriptionId, callingPackage,
                resultCallback);
    }

    private void callUpdateSubscriptionNickname(int subscriptionId, String iccid, String nickname,
            final boolean complete, final int result, String callingPackage) {
        PendingIntent resultCallback = PendingIntent.getBroadcast(mContext, 0, new Intent(),
                PendingIntent.FLAG_IMMUTABLE);
        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Exception {
                EuiccConnector.UpdateNicknameCommandCallback cb = invocation
                        .getArgument(3 /* resultCallback */);
                if (complete) {
                    cb.onUpdateNicknameComplete(result);
                } else {
                    cb.onEuiccServiceUnavailable();
                }
                return null;
            }
        }).when(mMockConnector).updateSubscriptionNickname(anyInt(), eq(iccid), eq(nickname),
                any());
        mController.updateSubscriptionNickname(CARD_ID, subscriptionId, nickname, callingPackage,
                resultCallback);
    }

    private void callEraseSubscriptions(final boolean complete, final int result) {
        PendingIntent resultCallback = PendingIntent.getBroadcast(mContext, 0, new Intent(),
                PendingIntent.FLAG_IMMUTABLE);
        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Exception {
                EuiccConnector.EraseCommandCallback cb = invocation
                        .getArgument(1 /* resultCallback */);
                if (complete) {
                    cb.onEraseComplete(result);
                } else {
                    cb.onEuiccServiceUnavailable();
                }
                return null;
            }
        }).when(mMockConnector).eraseSubscriptions(anyInt(), any());
        mController.eraseSubscriptions(CARD_ID, resultCallback);
    }

    private void callEraseSubscriptionsWithOptions(final boolean complete, final int result) {
        PendingIntent resultCallback = PendingIntent.getBroadcast(mContext, 0, new Intent(),
                PendingIntent.FLAG_IMMUTABLE);
        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Exception {
                EuiccConnector.EraseCommandCallback cb = invocation
                        .getArgument(2 /* resultCallback */);
                if (complete) {
                    cb.onEraseComplete(result);
                } else {
                    cb.onEuiccServiceUnavailable();
                }
                return null;
            }
        }).when(mMockConnector).eraseSubscriptionsWithOptions(anyInt(), anyInt(), any());
        mController.eraseSubscriptionsWithOptions(CARD_ID,
                RESET_OPTION_DELETE_OPERATIONAL_PROFILES, resultCallback);
    }

    private void callRetainSubscriptionsForFactoryReset(final boolean complete, final int result) {
        PendingIntent resultCallback = PendingIntent.getBroadcast(mContext, 0, new Intent(),
                PendingIntent.FLAG_IMMUTABLE);
        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Exception {
                EuiccConnector.RetainSubscriptionsCommandCallback cb = invocation
                        .getArgument(1 /* resultCallback */);
                if (complete) {
                    cb.onRetainSubscriptionsComplete(result);
                } else {
                    cb.onEuiccServiceUnavailable();
                }
                return null;
            }
        }).when(mMockConnector).retainSubscriptions(anyInt(), any());
        mController.retainSubscriptionsForFactoryReset(CARD_ID, resultCallback);
    }

    private void verifyResolutionIntent(String euiccUiAction, @EuiccOperation.Action int action) {
        assertEquals(euiccUiAction, mController.mResolutionAction);
        assertNotNull(mController.mOp);
        assertEquals(action, mController.mOp.mAction);
    }

    private Intent verifyIntentSent(int resultCode, int detailedCode)
            throws RemoteException {
        assertNotNull(mController.mCallbackIntent);
        assertEquals(resultCode, mController.mResultCode);
        if (mController.mExtrasIntent == null) {
            assertEquals(0, detailedCode);
        } else {
            assertEquals(detailedCode,
                    mController.mExtrasIntent.getIntExtra(
                            EuiccManager.EXTRA_EMBEDDED_SUBSCRIPTION_DETAILED_CODE, 0));
        }
        return mController.mExtrasIntent;
    }

    public enum AvailableMemoryCallbackStatus {
        SUCCESS,
        EXCEPTION,
        UNAVAILABLE
    }
}
