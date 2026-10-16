package com.wuyi.kebiao;

/**
 * 开源许可条目。
 *
 * 清单来自 gradle/libs.versions.toml 里实际引入的依赖 —— 只列真正用到的，
 * 不把没用上的库也写进来（那反而误导）。
 */
public class LicenseEntry {

    /** 组件名 */
    public final String name;
    /** 版本，可为空 */
    public final String version;
    /** 许可协议，如 "Apache License 2.0" */
    public final String license;
    /** 版权声明 / 补充说明，可为空 */
    public final String copyright;
    /** 是否为分组标题行（如「第三方组件」），标题行不显示协议与版权 */
    public final boolean header;

    public LicenseEntry(String name, String version, String license, String copyright) {
        this(name, version, license, copyright, false);
    }

    private LicenseEntry(String name, String version, String license,
                         String copyright, boolean header) {
        this.name = name == null ? "" : name;
        this.version = version == null ? "" : version;
        this.license = license == null ? "" : license;
        this.copyright = copyright == null ? "" : copyright;
        this.header = header;
    }

    /** 构造一个分组标题行 */
    public static LicenseEntry header(String title) {
        return new LicenseEntry(title, "", "", "", true);
    }

    /** 供界面显示的第一行：组件名 + 版本 */
    public String title() {
        return version.isEmpty() ? name : name + " " + version;
    }
}
