# Camera wordmarks — resource provenance

This directory ships 24 monochrome Android VectorDrawable camera-brand wordmarks
(`ic_camera_*_wordmark.xml`). Each is a single `#FFFFFFFF` fill tinted at runtime;
the viewport is the **tight ink bounding box** of the kept letterforms (uniform
margin added), so the aspect below is the exact tight ratio used to size
`width = brandHeight * aspect`. Nothing was derived from any reference APK.

Matching (`CameraBrands.find`) is case-insensitive and robust to whitespace and
punctuation/company-name variants. Trademarks remain the property of their owners;
this is non-commercial camera identification only and implies no affiliation or
endorsement. Public redistribution should independently re-check trademark and
asset-license terms.

## Existing wordmarks (pre-v1.9.23)

| Brand | Resource | Aspect | Source URL | License / trademark note |
|---|---|---|---|---|
| Nikon | `ic_camera_nikon_wordmark.xml` | 3.96 (99:25) | https://logo-teka.com/wp-content/uploads/2025/10/nikon-logo.svg (page https://logo-teka.com/nikon/) | logo-teka.com community vector; only the NIKON letter paths kept (yellow square + light rays dropped). Trademark of Nikon Corporation. Identification only. |
| vivo | `ic_camera_vivo_wordmark.xml` | ~3.81 | https://logo-teka.com/wp-content/uploads/2025/07/vivo-logo.svg | logo-teka.com community vector; monochrome conversion. Trademark of vivo Mobile Communication Co., Ltd. Identification only. |

## Camera makers (v1.9.23)

| Brand | Resource | Aspect | Source URL | License / trademark note |
|---|---|---|---|---|
| Canon | `ic_camera_canon_wordmark.xml` | 4.5007 | https://logo-teka.com/wp-content/uploads/2025/10/canon-logo.svg | logo-teka.com community vector. Trademark of Canon Inc. |
| Fujifilm | `ic_camera_fujifilm_wordmark.xml` | 5.5965 | https://cdn.jsdelivr.net/npm/simple-icons@latest/icons/fujifilm.svg | Simple Icons, CC0 1.0. Trademark of Fujifilm Holdings Corp. |
| Hasselblad | `ic_camera_hasselblad_wordmark.xml` | 11.8921 | https://logo-teka.com/wp-content/uploads/2025/10/hasselblad-wordmark-logo.svg | logo-teka.com dedicated wordmark. Trademark of Hasselblad AB. |
| Leica | `ic_camera_leica_wordmark.xml` | 1.0000 | https://cdn.jsdelivr.net/npm/simple-icons@latest/icons/leica.svg | Simple Icons, CC0 1.0. Round Leitz/Leica badge; ink box is genuinely square. Trademark of Leica Camera AG. |
| Panasonic | `ic_camera_panasonic_wordmark.xml` | 6.1168 | https://cdn.jsdelivr.net/npm/simple-icons@latest/icons/panasonic.svg | Simple Icons, CC0 1.0. Trademark of Panasonic Holdings Corp. Bare "Panasonic" EXIF maps here. |
| Lumix | `ic_camera_lumix_wordmark.xml` | 4.8396 | https://worldvectorlogo.com/logos/lumix.svg | Worldvectorlogo; white background path dropped. Trademark of Panasonic / LUMIX. Only explicit "Lumix"/"Panasonic Lumix" EXIF maps here (most LUMIX bodies report Make=Panasonic). |
| Olympus | `ic_camera_olympus_wordmark.xml` | 4.9747 | https://i.logos-download.com/4529/985-b7589fd8fd4ecf0aaa88ccfe8e996bb1.svg/Olympus_Logo_2000.svg | Logos-download.com tagline-free OLYMPUS wordmark; transforms flattened, slogan-free. Trademark of Olympus Corp. / OM Digital Solutions. |
| Pentax | `ic_camera_pentax_wordmark.xml` | 4.8363 | https://logo-teka.com/wp-content/uploads/2025/10/pentax-logo.svg | logo-teka.com vector. Trademark of Ricoh Imaging Co., Ltd. |
| Ricoh | `ic_camera_ricoh_wordmark.xml` | 5.2560 | https://logo-teka.com/wp-content/uploads/2025/10/ricoh-wordmark-logo.svg | logo-teka.com dedicated wordmark. Trademark of Ricoh Company, Ltd. |
| Sony | `ic_camera_sony_wordmark.xml` | 5.3354 | https://cdn.jsdelivr.net/npm/simple-icons@latest/icons/sony.svg | Simple Icons, CC0 1.0. Trademark of Sony Group Corp. |
| Zeiss | `ic_camera_zeiss_wordmark.xml` | 3.9919 | https://i.logos-download.com/8407/1923-e12af2acc1801cde947589226dc62a46.svg/Carl_Zeiss_Logo_1991.svg | Logos-download.com; blue badge background dropped, ZEISS letterforms kept. Trademark of Carl Zeiss AG. |

## Phone makers (v1.9.23)

| Brand | Resource | Aspect | Source URL | License / trademark note |
|---|---|---|---|---|
| Honor | `ic_camera_honor_wordmark.xml` | 5.1996 | https://cdn.jsdelivr.net/npm/simple-icons@16.34.0/icons/honor.svg | Simple Icons v16.34.0, CC0 1.0. Trademark of Honor Device Co., Ltd. |
| Huawei | `ic_camera_huawei_wordmark.xml` | 4.1689 | https://www.vectorlogo.zone/logos/huawei/huawei-ar21.svg | vectorlogo.zone community set; petal + wordmark lockup. Trademark of Huawei Technologies Co., Ltd. |
| iQOO | `ic_camera_iqoo_wordmark.xml` | 4.2350 | https://assets.zonalogo.com/hardware-electronics/iqoo.com/logo-black-1778495771649-71082.svg | zonalogo.com monochrome asset. Trademark of iQOO / vivo Mobile Communication Corp. |
| Meizu | `ic_camera_meizu_wordmark.xml` | 5.5551 | https://i.logos-download.com/5267/33854-4771b72f54eda373f8fbe65b30bcc122.svg/Meizu_Logo_2020.svg | logos-download.com, Meizu Logo 2020. Trademark of Meizu Technology Co., Ltd. |
| OPPO | `ic_camera_oppo_wordmark.xml` | 4.2200 | https://assets.zonalogo.com/hardware-electronics/oppo.com/logo-black-1778409508862-42363.svg | zonalogo.com monochrome asset. Trademark of Guangdong OPPO Mobile Telecommunications Corp., Ltd. |
| OnePlus | `ic_camera_oneplus_wordmark.xml` | 4.2100 | https://assets.zonalogo.com/hardware-electronics/oneplus.com/logo-black-1780741095656-54872.svg | zonalogo.com; '1+' mark + ONEPLUS lockup. Trademark of OnePlus Technology (Shenzhen) Co., Ltd. |
| Xiaomi | `ic_camera_xiaomi_wordmark.xml` | 6.0033 | https://assets.zonalogo.com/hardware-electronics/mi.com/wordmark-black-1778403965860-81668.svg | zonalogo.com monochrome wordmark. Trademark of Xiaomi Communications Co., Ltd. |

## Tech / imaging makers (v1.9.23)

| Brand | Resource | Aspect | Source URL | License / trademark note |
|---|---|---|---|---|
| Apple | `ic_camera_apple_wordmark.xml` | 0.8231 | https://cdn.jsdelivr.net/gh/simple-icons/simple-icons@develop/icons/apple.svg | Simple Icons path data, CC0 1.0. Bitten-apple silhouette (the photographic brand mark); trademark of Apple Inc. |
| DJI | `ic_camera_dji_wordmark.xml` | 1.6556 | https://cdn.jsdelivr.net/gh/simple-icons/simple-icons@develop/icons/dji.svg | Simple Icons, CC0 1.0. Trademark of SZ DJI Technology Co., Ltd. |
| Insta360 | `ic_camera_insta360_wordmark.xml` | 0.9813 | https://cdn.jsdelivr.net/gh/simple-icons/simple-icons@develop/icons/insta360.svg | Simple Icons, CC0 1.0. Trademark of Arashi Vision Inc. / Shanghai Arashi Network Technology Co., Ltd. |
| Samsung | `ic_camera_samsung_wordmark.xml` | 6.2300 | https://cdn.jsdelivr.net/gh/simple-icons/simple-icons@develop/icons/samsung.svg | Simple Icons, CC0 1.0. Trademark of Samsung Electronics Co., Ltd. |
