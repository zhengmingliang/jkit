package com.alianga.jkit.sql.entity.fixture;

import javax.persistence.Embeddable;

/**
 * 嵌入地址。
 *
 * @author 郑明亮
 */
@Embeddable
public class Address {
    private String city;
    private String street;

    /**
     * @return city
     */
    public String getCity() {
        return city;
    }

    /**
     * @param city city
     */
    public void setCity(String city) {
        this.city = city;
    }

    /**
     * @return street
     */
    public String getStreet() {
        return street;
    }

    /**
     * @param street street
     */
    public void setStreet(String street) {
        this.street = street;
    }
}
