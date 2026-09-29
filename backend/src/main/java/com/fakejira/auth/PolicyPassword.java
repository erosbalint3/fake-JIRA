package com.fakejira.auth;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** The field must satisfy the admin's password policy; the message names what is missing. */
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = PolicyPassword.Validator.class)
public @interface PolicyPassword {

    String message() default "Password does not meet the requirements";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<PolicyPassword, String> {

        private final PasswordPolicy policy;

        public Validator(PasswordPolicy policy) {
            this.policy = policy;
        }

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            String problem = policy.problem(value);
            if (problem == null) {
                return true;
            }
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(problem.replace("{", "\\{")).addConstraintViolation();
            return false;
        }
    }
}
