/**
 * 故意放在默认包（无名包）下的 POJO，用于复现并回归验证
 * {@code ClassStrucWrap.getPackage()} 与 JSON 代码生成对默认包类的处理。
 * 默认包类的 {@link Class#getPackage()} 返回 {@code null}、{@link Class#getCanonicalName()} 返回 {@code null}，
 * 历史实现在这两处会直接抛出 NullPointerException。
 */
public class DefaultPackageBean {
    private String name;
    private int age;

    public DefaultPackageBean() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getAge() {
        return age;
    }

    public void setAge(int age) {
        this.age = age;
    }
}
