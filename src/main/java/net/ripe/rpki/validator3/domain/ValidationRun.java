package net.ripe.rpki.validator3.domain;

import lombok.Getter;
import net.ripe.rpki.commons.validation.ValidationLocation;
import net.ripe.rpki.commons.validation.ValidationResult;
import net.ripe.rpki.commons.validation.ValidationStatus;

import javax.persistence.*;
import javax.validation.constraints.NotNull;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static net.ripe.rpki.validator3.domain.ValidationCheck.mapStatus;


/**
 * Represents the a single run of validating a single trust anchor and all it's child CAs and related RPKI objects.
 */
@Entity
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "TYPE", columnDefinition = "CHAR(2)")
public abstract class ValidationRun extends AbstractEntity {

    public enum Status {
        RUNNING,
        SUCCEEDED,
        FAILED
    }

    @Basic
    @Getter
    private Instant completedAt;

    @Enumerated(value = EnumType.STRING)
    @NotNull
    @Getter
    private Status status = Status.RUNNING;

    @OneToMany(cascade = CascadeType.ALL, mappedBy = "validationRun")
    @Getter
    private List<ValidationCheck> validationChecks = new ArrayList<>();

    @SuppressWarnings("unused")
    protected ValidationRun() {
        super();
    }

    public abstract String getType();

    public boolean isSucceeded() {
        return this.status == Status.SUCCEEDED;
    }
    public boolean isFailed() {
        return this.status == Status.FAILED;
    }

    public void setSucceeded() {
        this.completedAt = Instant.now();
        this.status = Status.SUCCEEDED;
    }

    public void setFailed() {
        this.completedAt = Instant.now();
        this.status = Status.FAILED;
    }

    public void completeWith(ValidationResult validationResult) {
        for (ValidationLocation location : validationResult.getValidatedLocations()) {
            for (net.ripe.rpki.commons.validation.ValidationCheck check : validationResult.getAllValidationChecksForLocation(location)) {
                if (check.getStatus() != ValidationStatus.PASSED) {
                    ValidationCheck validationCheck = new ValidationCheck(this, location.getName(), check);
                    addCheck(validationCheck);
                }
            }
        }

        if (!isFailed()) {
            setSucceeded();
        }
    }

    public void addCheck(ValidationCheck validationCheck) {
        this.validationChecks.add(validationCheck);
    }

    public void addChecks(ValidationResult validationResult) {
        validationResult.getAllValidationChecksForCurrentLocation().forEach(c -> {
            if (c.getStatus() != ValidationStatus.PASSED) {
                final ValidationCheck.Status status = mapStatus(c.getStatus());
                addCheck(new ValidationCheck(this, validationResult.getCurrentLocation().getName(), status, c.getKey(), c.getParams()));
            }
        });
    }

    public abstract void visit(Visitor visitor);

    public interface Visitor<T> {
        default void accept(CertificateTreeValidationRun validationRun) {
        }
        default void accept(RrdpRepositoryValidationRun validationRun) {
        }
        default void accept(RsyncRepositoryValidationRun validationRun) {
        }
        default void accept(TrustAnchorValidationRun validationRun) {
        }
    }
}
