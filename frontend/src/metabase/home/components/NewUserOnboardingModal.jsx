/* @flow */
import React, { Component } from "react";
import StepIndicators from "metabase/components/StepIndicators";
import RetinaImage from "react-retina-image";
import { t } from "c-3po";
import MetabaseSettings from "metabase/lib/settings";

type Props = {
  onClose: () => void,
};

type State = {
  step: number,
};

const STEPS = [
  {
    title: t`Haz preguntas y explora`,
    text: t`Haz clic en los cuadros o tablas para explorar, o haz una nueva pregunta usando nuestra fácil interfaz o el potente editor SQL.`,
    image: (
      <RetinaImage
        className="absolute full"
        style={{ top: 30 }}
        src={`app/assets/img/welcome-modal-1.png`}
      />
    ),
  },
  {
    title: t`Haz tus propios gráficos`,
    text: t`Crea gráficos de líneas, gráficos de dispersión, mapas y más.`,
    image: (
      <RetinaImage
        className="absolute ml-auto mr-auto inline-block left right"
        style={{ bottom: -20 }}
        src={`app/assets/img/welcome-modal-2.png`}
      />
    ),
  },
  {
    title: t`Comparte lo que encuentres`,
    text: t`Crea poderosos y flexibles tableros de control, y envía actualizaciones periódicas vía Email o Slack.`,
    image: (
      <RetinaImage
        className="absolute ml-auto mr-auto inline-block left right"
        style={{ bottom: -30 }}
        src={`app/assets/img/welcome-modal-3.png`}
      />
    ),
  },
];

export default class NewUserOnboardingModal extends Component {
  props: Props;
  state: State = {
    step: 1,
  };

  nextStep = () => {
    const stepCount = MetabaseSettings.get("has_sample_dataset") ? 3 : 2;
    const nextStep = this.state.step + 1;

    if (nextStep <= stepCount) {
      this.setState({ step: nextStep });
    } else {
      this.props.onClose();
    }
  };

  render() {
    const { step } = this.state;
    const currentStep = STEPS[step - 1];

    return (
      <div>
        <OnboardingImages currentStep={currentStep} />
        <div className="p4 pb3 text-centered">
          <h2>{currentStep.title}</h2>
          <p
            className="ml-auto mr-auto text-paragraph"
            style={{ maxWidth: 420 }}
          >
            {currentStep.text}
          </p>
          <div className="flex align-center py2 relative">
            <div className="ml-auto mr-auto">
              <StepIndicators
                currentStep={step}
                steps={STEPS}
                goToStep={step => this.setState({ step })}
              />
            </div>
            <a
              className="link flex-align-right text-bold absolute right"
              onClick={() => this.nextStep()}
            >
              {step === 3 ? t`Vámonos` : t`Siguiente`}
            </a>
          </div>
        </div>
      </div>
    );
  }
}

const OnboardingImages = ({ currentStep }, { currentStep: object }) => (
  <div
    style={{
      position: "relative",
      backgroundColor: "#F5F9FE",
      borderBottom: "1px solid #DCE1E4",
      height: 254,
      paddingTop: "3em",
      paddingBottom: "3em",
    }}
  >
    {currentStep.image}
  </div>
);
