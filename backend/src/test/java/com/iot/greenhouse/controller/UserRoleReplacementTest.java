package com.iot.greenhouse.controller;

import com.iot.greenhouse.entity.Role;
import com.iot.greenhouse.entity.User;
import com.iot.greenhouse.entity.UserSession;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

class UserRoleReplacementTest {

    @Test
    void replaceRolesCollection() {
        Map<String, Object> settings = new HashMap<>();
        settings.put("hibernate.connection.driver_class", "org.h2.Driver");
        settings.put("hibernate.connection.url", "jdbc:h2:mem:rolerepro2;DB_CLOSE_DELAY=-1");
        settings.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
        settings.put("hibernate.hbm2ddl.auto", "create-drop");
        settings.put("hibernate.show_sql", "true");

        StandardServiceRegistry registry =
                new StandardServiceRegistryBuilder().applySettings(settings).build();
        MetadataSources sources = new MetadataSources(registry);
        sources.addAnnotatedClass(User.class);
        sources.addAnnotatedClass(Role.class);
        sources.addAnnotatedClass(UserSession.class);
        Metadata metadata = sources.buildMetadata();

        Long userId;
        try (SessionFactory sessionFactory = metadata.buildSessionFactory()) {
            try (Session session = sessionFactory.openSession()) {
                Transaction tx = session.beginTransaction();
                Role viewer = Role.builder().name("VIEWER").build();
                Role operator = Role.builder().name("OPERATOR").build();
                session.persist(viewer);
                session.persist(operator);
                User user = User.builder()
                        .username("tech").email("t@t.io").passwordHash("x")
                        .status("ACTIVE").roles(Set.of(viewer)).build();
                session.persist(user);
                tx.commit();
                userId = user.getId();
            }

            // Mirror UserRestController.updateRole: load, replace roles, save (merge).
            try (Session session = sessionFactory.openSession()) {
                Transaction tx = session.beginTransaction();
                User found = session.find(User.class, userId);
                Role operator = session.createQuery("from Role r where r.name = :n", Role.class)
                        .setParameter("n", "OPERATOR").uniqueResult();
                // Same as UserRestController.updateRole: the replacement collection must be
                // mutable, otherwise flush throws UnsupportedOperationException (500 in prod).
                found.setRoles(new HashSet<>(Set.of(operator)));
                session.merge(found);
                tx.commit();
                System.out.println("HIBERNATE_REPRO_OK roles=" + found.getRoles());
            }
        }
        registry.close();
    }
}
