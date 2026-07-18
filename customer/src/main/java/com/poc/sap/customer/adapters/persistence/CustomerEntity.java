package com.poc.sap.customer.adapters.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Entidad JPA que mapea la tabla legacy Customer en SQL Server.
 * Esquema plano: los datos se agrupan en columnas que el dominio reparte entre
 * las 4 features (address, fiscal, contact, banking).
 */
@Entity
@Table(name = "customers")
public class CustomerEntity {

    @Id
    private String id;
    private String code;
    private String name;
    private String status;

    private String street;
    private String city;
    private String postalCode;
    private String country;
    private String region;

    private String taxId;
    private String vatNumber;
    private String legalName;
    private String taxResidency;

    private String email;
    private String phone;
    private String fax;
    private String website;

    private String iban;
    private String bic;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getStreet() { return street; }
    public void setStreet(String street) { this.street = street; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public String getPostalCode() { return postalCode; }
    public void setPostalCode(String postalCode) { this.postalCode = postalCode; }
    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getTaxId() { return taxId; }
    public void setTaxId(String taxId) { this.taxId = taxId; }
    public String getVatNumber() { return vatNumber; }
    public void setVatNumber(String vatNumber) { this.vatNumber = vatNumber; }
    public String getLegalName() { return legalName; }
    public void setLegalName(String legalName) { this.legalName = legalName; }
    public String getTaxResidency() { return taxResidency; }
    public void setTaxResidency(String taxResidency) { this.taxResidency = taxResidency; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getFax() { return fax; }
    public void setFax(String fax) { this.fax = fax; }
    public String getWebsite() { return website; }
    public void setWebsite(String website) { this.website = website; }
    public String getIban() { return iban; }
    public void setIban(String iban) { this.iban = iban; }
    public String getBic() { return bic; }
    public void setBic(String bic) { this.bic = bic; }
}