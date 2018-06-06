/* eslint "react/prop-types": "warn" */
import React, { Component } from "react";
import PropTypes from "prop-types";
import { t } from "c-3po";
import FormField from "metabase/components/form/FormField.jsx";
import FormLabel from "metabase/components/form/FormLabel.jsx";
import FormMessage from "metabase/components/form/FormMessage.jsx";
import MetabaseAnalytics from "metabase/lib/analytics";
import MetabaseSettings from "metabase/lib/settings";
import MetabaseUtils from "metabase/lib/utils";

import StepTitle from "./StepTitle.jsx";
import CollapsedStep from "./CollapsedStep.jsx";

import _ from "underscore";
import cx from "classnames";

export default class UserStep extends Component {
  constructor(props, context) {
    super(props, context);
    this.state = {
      fieldValues: this.props.userDetails || {
        first_name: "",
        last_name: "",
        email: "",
        password: "",
        site_name: "",
      },
      formError: null,
      passwordError: null,
      valid: false,
      validPassword: false,
    };
  }

  static propTypes = {
    stepNumber: PropTypes.number.isRequired,
    activeStep: PropTypes.number.isRequired,
    setActiveStep: PropTypes.func.isRequired,

    userDetails: PropTypes.object,
    setUserDetails: PropTypes.func.isRequired,
    validatePassword: PropTypes.func.isRequired,
  };

  validateForm = () => {
    let { fieldValues, valid, validPassword } = this.state;
    let isValid = true;

    // required: first_name, last_name, email, password
    Object.keys(fieldValues).forEach(fieldName => {
      if (MetabaseUtils.isEmpty(fieldValues[fieldName])) isValid = false;
    });

    if (!validPassword) {
      isValid = false;
    }

    if (isValid !== valid) {
      this.setState({
        valid: isValid,
      });
    }
  };

  onPasswordBlur = async e => {
    try {
      await this.props.validatePassword(this.state.fieldValues.password);

      this.setState(
        {
          passwordError: null,
          validPassword: true,
        },
        this.validateForm,
      );
    } catch (error) {
      this.setState({
        passwordError: error.data.errors.password,
        validPassword: false,
      });

      MetabaseAnalytics.trackEvent("Setup", "Error", "password validation");
    }
  };

  formSubmitted = e => {
    const { fieldValues } = this.state;

    e.preventDefault();

    this.setState({
      formError: null,
    });

    let formErrors = { data: { errors: {} } };

    // validate email address
    if (!MetabaseUtils.validEmail(fieldValues.email)) {
      formErrors.data.errors.email = t`Formato del correo electrónico no válido`;
    }

    // TODO - validate password complexity

    // validate password match
    if (fieldValues.password !== fieldValues.password_confirm) {
      formErrors.data.errors.password_confirm = t`Las contraseñas no coinciden`;
    }

    if (_.keys(formErrors.data.errors).length > 0) {
      this.setState({
        formError: formErrors,
      });
      return;
    }

    this.props.setUserDetails({
      nextStep: this.props.stepNumber + 1,
      details: _.omit(fieldValues, "password_confirm"),
    });

    MetabaseAnalytics.trackEvent("Setup", "User Details Step");
  };

  updateFieldValue = (fieldName, value) => {
    this.setState(
      {
        fieldValues: {
          ...this.state.fieldValues,
          [fieldName]: value,
        },
      },
      this.validateForm,
    );
  };

  onFirstNameChange = e => this.updateFieldValue("first_name", e.target.value);
  onLastNameChange = e => this.updateFieldValue("last_name", e.target.value);
  onEmailChange = e => this.updateFieldValue("email", e.target.value);
  onPasswordChange = e => this.updateFieldValue("password", e.target.value);
  onPasswordConfirmChange = e =>
    this.updateFieldValue("password_confirm", e.target.value);
  onSiteNameChange = e => this.updateFieldValue("site_name", e.target.value);

  render() {
    let { activeStep, setActiveStep, stepNumber, userDetails } = this.props;
    let { formError, passwordError, valid } = this.state;

    const passwordComplexityDesc = MetabaseSettings.passwordComplexity();
    const stepText =
      activeStep <= stepNumber
        ? t`¿Cómo deberíamos llamarte?`
        : t`Hola, ${userDetails.first_name}. gusto en conocerte!`;

    if (activeStep !== stepNumber) {
      return (
        <CollapsedStep
          stepNumber={stepNumber}
          stepCircleText="1"
          stepText={stepText}
          isCompleted={activeStep > stepNumber}
          setActiveStep={setActiveStep}
        />
      );
    } else {
      return (
        <section className="SetupStep SetupStep--active rounded full relative">
          <StepTitle title={stepText} circleText={"1"} />
          <form
            name="userForm"
            onSubmit={this.formSubmitted}
            noValidate
            className="mt2"
          >
            <FormField
              className="Grid mb3"
              fieldName="first_name"
              formError={formError}
            >
              <div>
                <FormLabel
                  title={t`Nombre(s)`}
                  fieldName="first_name"
                  formError={formError}
                />
                <input
                  className="Form-input Form-offset full"
                  name="first_name"
                  defaultValue={userDetails ? userDetails.first_name : ""}
                  placeholder="Johnny"
                  required
                  autoFocus={true}
                  onChange={this.onFirstNameChange}
                />
                <span className="Form-charm" />
              </div>
              <div>
                <FormLabel
                  title={t`Apellido(s)`}
                  fieldName="last_name"
                  formError={formError}
                />
                <input
                  className="Form-input Form-offset"
                  name="last_name"
                  defaultValue={userDetails ? userDetails.last_name : ""}
                  placeholder="Appleseed"
                  required
                  onChange={this.onLastNameChange}
                />
                <span className="Form-charm" />
              </div>
            </FormField>

            <FormField fieldName="email" formError={formError}>
              <FormLabel
                title={t`Correo electrónico`}
                fieldName="email"
                formError={formError}
              />
              <input
                className="Form-input Form-offset full"
                name="email"
                defaultValue={userDetails ? userDetails.email : ""}
                placeholder="youlooknicetoday@email.com"
                required
                onChange={this.onEmailChange}
              />
              <span className="Form-charm" />
            </FormField>

            <FormField
              fieldName="password"
              formError={formError}
              error={passwordError !== null}
            >
              <FormLabel
                title={t`Crea una contraseña`}
                fieldName="password"
                formError={formError}
                message={passwordError}
              />
              <span
                style={{ fontWeight: "normal" }}
                className="Form-label Form-offset"
              >
                {passwordComplexityDesc}
              </span>
              <input
                className="Form-input Form-offset full"
                name="password"
                type="password"
                defaultValue={userDetails ? userDetails.password : ""}
                placeholder={t`Shhh...`}
                required
                onChange={this.onPasswordChange}
                onBlur={this.onPasswordBlur}
              />
              <span className="Form-charm" />
            </FormField>

            <FormField fieldName="password_confirm" formError={formError}>
              <FormLabel
                title={t`Confirma la contraseña`}
                fieldName="password_confirm"
                formError={formError}
              />
              <input
                className="Form-input Form-offset full"
                name="password_confirm"
                type="password"
                defaultValue={userDetails ? userDetails.password : ""}
                placeholder={t`Shhh... pero una vez más para hacerlo bien`}
                required
                onChange={this.onPasswordConfirmChange}
              />
              <span className="Form-charm" />
            </FormField>

            <FormField fieldName="site_name" formError={formError}>
              <FormLabel
                title={t`Nombre de tu compañía o equipo de trabajo`}
                fieldName="site_name"
                formError={formError}
              />
              <input
                className="Form-input Form-offset full"
                name="site_name"
                type="text"
                defaultValue={userDetails ? userDetails.site_name : ""}
                placeholder={t`Departamento`}
                required
                onChange={this.onSiteNameChange}
              />
              <span className="Form-charm" />
            </FormField>

            <div className="Form-actions">
              <button
                className={cx("Button", { "Button--primary": valid })}
                disabled={!valid}
              >
                {t`Siguiente`}
              </button>
              <FormMessage />
            </div>
          </form>
        </section>
      );
    }
  }
}
