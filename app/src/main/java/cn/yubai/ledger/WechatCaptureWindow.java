package cn.yubai.ledger;

import java.util.Set;

/** Exact result Activities verified in the installed WeChat 8.0.76 manifest.
 * Input, password, chat, wallet balance and incoming-payment pages are excluded. */
final class WechatCaptureWindow {
    private static final String DETAIL="com.tencent.mm.plugin.remittance.ui.RemittanceDetailUI";
    private static final Set<String> RESULTS=Set.of(
        "com.tencent.mm.plugin.wallet_core.ui.WalletOrderInfoUI",
        "com.tencent.mm.plugin.wallet_core.ui.WalletOrderInfoOldUI",
        "com.tencent.mm.plugin.wallet_core.ui.WalletOrderInfoNewUI",
        "com.tencent.mm.plugin.wallet_core.ui.WalletMixOrderInfoProxyUI",
        "com.tencent.mm.plugin.wallet_core.ui.WalletMixOrderInfoUI",
        "com.tencent.mm.plugin.remittance.ui.RemittanceResultUI",
        "com.tencent.mm.plugin.remittance.ui.RemittanceResultNewUI",
        "com.tencent.mm.plugin.remittance.ui.RemittanceResultOldUI",
        "com.tencent.mm.plugin.remittance.ui.RemittanceBusiResultUI");
    static boolean result(String name){return RESULTS.contains(name);}
    static boolean allowed(String name){return DETAIL.equals(name)||result(name);}
}
