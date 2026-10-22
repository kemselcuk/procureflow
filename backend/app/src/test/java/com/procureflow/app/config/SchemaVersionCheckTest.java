package com.procureflow.app.config;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The check is conditional: web-slice contexts have no DataSource and must keep starting without
 * attempting a database lookup.
 */
class SchemaVersionCheckTest {
    @Test
    void skipsWhenNoDataSourceIsAvailable() {
        var environment = new MockEnvironment().withProperty(SchemaVersionCheck.EXPECTED_VERSION_PROPERTY, "6");
        var check = new SchemaVersionCheck(new DefaultListableBeanFactory().getBeanProvider(DataSource.class), environment);
        check.afterSingletonsInstantiated();
        assertThat(check.verifiedVersion()).isNull();
    }
}
