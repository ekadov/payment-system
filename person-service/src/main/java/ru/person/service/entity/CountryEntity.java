package ru.person.service.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.time.Instant;

@Entity
@Table(name = "countries", schema = "person")
@Setter
@Getter
@Accessors(chain = true)
public class CountryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false, updatable = false)
    private Instant created;

    @Column(nullable = false)
    private Instant updated;

    @Column(nullable = false, length = 32)
    private String name;

    @Column(nullable = false, length = 2)
    private String alpha2;

    @Column(nullable = false, length = 3)
    private String alpha3;

    @Column(nullable = false, length = 32)
    private String status;

}
