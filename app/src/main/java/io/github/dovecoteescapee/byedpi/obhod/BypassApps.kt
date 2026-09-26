package io.github.dovecoteescapee.byedpi.obhod

/**
 * Apps that must use the underlay network (not the local VPN).
 *
 * Reasons:
 * - TikTok / some CN apps break under -U (no QUIC) or TLS desync
 * - Russian banking / food / shops detect VpnService and throttle or refuse
 * - They are not DPI-blocked on Tele2 and should stay fast on LTE/Wi‑Fi
 */
object BypassApps {
    val tiktokPackages = listOf(
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",
        "com.ss.android.ugc.aweme",
        "com.ss.android.ugc.tiktok",
    )

    /**
     * Discord stays IN the tunnel (soft/multisplit group). Tele2 blocks it on underlay.
     * Do not exclude — underlay = skeleton.
     */
    val discordPackages = emptyList<String>()

    /**
     * Food, banks, marketplaces, Yandex, VK, gov, delivery —
     * must not see TRANSPORT_VPN (VpnService key / NetworkCapabilities).
     */
    val russianPackages = listOf(
        // Вкусно — и точка / BK / food
        "com.apegroup.mcdonaldsrussia",
        "ru.vkusnoitochka",
        "ru.burgerking",
        "ru.vkusvill",
        "ru.sbcs.store",
        "com.deliveryclub",
        "ru.foodfox.client",
        "ru.chibbis.app",
        "com.yandex.eda",
        // Banks / finance
        "ru.sberbankmobile",
        "com.idamob.tinkoff.android",
        "ru.vtb24.mobilebanking.android",
        "ru.alfabank.mobile.android",
        "com.umoney.android",
        "ru.raiffeisennews",
        "ru.gazprombank.android.mobilebank.newapp",
        "logo.com.mbanking",
        "ru.sber.nova",
        "com.sberbank.sberbankid",
        "ru.rosbank.android",
        "ru.otpbank.mobile",
        "ru.sovcomcard.halva.v1",
        "com.openbank",
        "ru.mw",
        "ru.yoomoney.app",
        "ru.qiwi.android",
        // Marketplaces
        "ru.ozon.app.android",
        "com.wildberries.ru",
        "ru.avito",
        "com.avito.android",
        "ru.dns.shop.android",
        "ru.letu",
        "com.kmall.citilink",
        "ru.mvideo.snp",
        "ru.lenta.mobilestore",
        "ru.pyaterochka.app.browser",
        "ru.tander.magnit",
        "ru.x5.crossdock",
        "ru.beru.android",
        "ru.aliexpress.buyer",
        "com.alibaba.aliexpresshd",
        "ru.lamoda.app",
        "com.joom",
        // Yandex ecosystem
        "ru.yandex.android",
        "ru.yandex.searchplugin",
        "ru.yandex.taxi",
        "ru.yandex.yandexmaps",
        "ru.yandex.metro",
        "ru.yandex.disk",
        "ru.yandex.mail",
        "ru.yandex.music",
        "ru.yandex.browser",
        "ru.yandex.androidkeyboard",
        "ru.yandex.telemost",
        "ru.kinopoisk",
        "ru.yandex.weatherplugin",
        "ru.yandex.bank",
        "ru.yandex.mobile.music",
        "com.yandex.browser",
        "com.yandex.mobile.navigator",
        "ru.yandex.auth",
        // VK / Mail / OK
        "com.vkontakte.android",
        "com.vk.im",
        "com.vk.clips",
        "ru.ok.android",
        "ru.mail.mailapp",
        "ru.mail.cloud",
        "ru.vk.store",
        "com.my.mail",
        // Gov / operators / tax
        "ru.rostel",
        "ru.gosuslugi.app",
        "ru.mos.udc",
        "ru.tele2.mytele2",
        "ru.mts.mymts",
        "com.megafon.mlk",
        "ru.beeline.services",
        "ru.nalog",
        "ru.mos.passport",
        "ru.rt.mobile.android",
        // Transport / tickets / other RU
        "ru.rzd.pass",
        "ru.auto.ara",
        "ru.hh.android",
        "com.pinterest", // often used RU; keep underlay if installed? skip
        "ru.cdek.mobile",
        "ru.pochta.android",
        "ru.dostavista.android",
        "com.russianpost.android",
        "ru.sberbank.sberbankid",
        "ru.sberbankmobile",
        "ru.vtb.mobilebanking.android",
        "ru.mts.money",
        "ru.mts.mtstv",
        "ru.beeline.services",
        "com.megafon.lifemain",
        "ru.yandex.taxi",
        "ru.yandex.android.navigator",
        "ru.yandex.ytaxi",
        "com.yandex.mobile.drive",
        "ru.domyland",
        "com.cian.main",
        "ru.avito.android",
        "com.wildberries.ru",
        "ru.ozon.app.android",
        "ru.dns.shop.android",
        "ru.sportmaster.app",
        "ru.okko.androidclient",
        "tv.ivi",
        "com.start.mobile",
        "ru.more.play",
    )

    val all: List<String> = (tiktokPackages + discordPackages + russianPackages)
        .filter { it != "com.pinterest" }
        .distinct()
}
