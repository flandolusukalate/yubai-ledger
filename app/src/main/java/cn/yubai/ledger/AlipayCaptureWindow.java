package cn.yubai.ledger;

import java.util.Set;

/** Exact classes present in the installed 12.12.30 manifest; result behavior still needs live testing. */
final class AlipayCaptureWindow {
    static final String PKG="com.eg.android.AlipayGphone";
    static final String BILL="com.alipay.mobile.nebulax.xriver.activity.XRiverActivity";
    static final String MSP="com.alipay.android.msp.ui.views.MspContainerActivity";
    private static final Set<String> RESULTS=Set.of(
        "com.alipay.mobile.payee.ui.PayeeQRPayerPayResultActivity",
        "com.alipay.mobile.onsitepay9.payer.PaySuccessActivity",
        "com.alipay.mobile.onsitepay9.payer.KoubeiPaySuccessActivity",MSP);
    static boolean result(String name){return RESULTS.contains(name);}
    static boolean allowed(String name){return BILL.equals(name)||result(name);}
}
