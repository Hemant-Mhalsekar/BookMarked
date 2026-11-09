package com.bookmarked.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Fixed roles seeded by Flyway (V2). Permissions are what endpoints check;
 * roles only group them.
 */
@Entity
@Table(name = "roles")
public class Role {

    public static final String CUSTOMER = "CUSTOMER";
    public static final String SUPER_ADMIN = "SUPER_ADMIN";
    public static final String INVENTORY_MANAGER = "INVENTORY_MANAGER";
    public static final String ORDER_MANAGER = "ORDER_MANAGER";
    public static final String DEMO_ADMIN = "DEMO_ADMIN";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String name;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "role_permissions",
            joinColumns = @JoinColumn(name = "role_id"),
            inverseJoinColumns = @JoinColumn(name = "permission_id"))
    private Set<Permission> permissions = new HashSet<>();

    protected Role() {
    }

    /** For tests; real roles come from the Flyway seed. */
    public Role(String name) {
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Set<Permission> getPermissions() {
        return permissions;
    }

    public List<String> permissionNames() {
        return permissions.stream().map(Permission::getName).sorted().toList();
    }
}
