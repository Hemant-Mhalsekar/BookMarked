package com.bookmarked.config;

import org.hibernate.boot.model.FunctionContributions;
import org.hibernate.boot.model.FunctionContributor;
import org.hibernate.type.StandardBasicTypes;

/**
 * Teaches Hibernate MySQL's full-text syntax so the search Specification can call
 * {@code match_against(title, description, :terms)}, rendered as
 * {@code match(title, description) against (:terms in boolean mode)}.
 * <p>
 * Registered through META-INF/services/org.hibernate.boot.model.FunctionContributor.
 */
public class MySqlFunctionContributor implements FunctionContributor {

    public static final String MATCH_AGAINST = "match_against";

    @Override
    public void contributeFunctions(FunctionContributions functionContributions) {
        functionContributions.getFunctionRegistry().registerPattern(
                MATCH_AGAINST,
                "match(?1, ?2) against (?3 in boolean mode)",
                functionContributions.getTypeConfiguration().getBasicTypeRegistry()
                        .resolve(StandardBasicTypes.DOUBLE));
    }
}
