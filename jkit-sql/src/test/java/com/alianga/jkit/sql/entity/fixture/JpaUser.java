package com.alianga.jkit.sql.entity.fixture;

import javax.persistence.Embedded;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Id;
import javax.persistence.OneToMany;
import javax.persistence.Table;

import java.util.List;

/**
 * JPA 用户：外键、枚举、集合、嵌入。
 *
 * @author 郑明亮
 */
@Entity
@Table(name = "jpa_user")
public class JpaUser {
    @Id
    private Long id;
    private String name;
    private JpaOrg org;
    @Enumerated(EnumType.ORDINAL)
    private Status status;
    @Embedded
    private Address address;
    @OneToMany
    private List<JpaUser> children;
    private List<String> tags;

    /**
     * @return id
     */
    public Long getId() {
        return id;
    }

    /**
     * @param id id
     */
    public void setId(Long id) {
        this.id = id;
    }

    /**
     * @return name
     */
    public String getName() {
        return name;
    }

    /**
     * @param name name
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * @return org
     */
    public JpaOrg getOrg() {
        return org;
    }

    /**
     * @param org org
     */
    public void setOrg(JpaOrg org) {
        this.org = org;
    }

    /**
     * @return status
     */
    public Status getStatus() {
        return status;
    }

    /**
     * @param status status
     */
    public void setStatus(Status status) {
        this.status = status;
    }

    /**
     * @return address
     */
    public Address getAddress() {
        return address;
    }

    /**
     * @param address address
     */
    public void setAddress(Address address) {
        this.address = address;
    }
}
