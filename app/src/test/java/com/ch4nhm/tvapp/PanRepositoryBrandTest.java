package com.ch4nhm.tvapp;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class PanRepositoryBrandTest {

    @Test
    public void recognizesAliyunDriveAliasesAsTheSameBrand() {
        assertEquals("aliyun", PanRepository.standaloneBrand("aliyundrive").id);
        assertEquals("阿里云盘", PanRepository.standaloneBrand("alipan").name);
        assertEquals("aliyun", PanRepository.brandForSourceText(
                "openapi.alipan.com 阿里云盘播放线路"));
    }

    @Test
    public void keepsUnknownPanBrandsAvailable() {
        PanRepository.Brand brand = PanRepository.standaloneBrand("custom-drive");

        assertEquals("custom-drive", brand.id);
        assertEquals("Custom drive", brand.name);
    }
}
