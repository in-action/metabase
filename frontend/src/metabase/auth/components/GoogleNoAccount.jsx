import React from "react";
import { t } from "c-3po";
import AuthScene from "./AuthScene.jsx";
import LogoIcon from "metabase/components/LogoIcon.jsx";
import BackToLogin from "./BackToLogin.jsx";

const GoogleNoAccount = () => (
  <div className="full-height bg-white flex flex-column flex-full md-layout-centered">
    <div className="wrapper">
      <div className="Login-wrapper Grid  Grid--full md-Grid--1of2">
        <div className="Grid-cell flex layout-centered text-brand">
          <LogoIcon className="Logo my4 sm-my0" width={66} height={85} />
        </div>
        <div className="Grid-cell text-centered bordered rounded shadowed p4">
          <h3 className="mt4 mb2">{t`No existe una cuenta de Metabase para esta cuenta de Google.`}</h3>
          <p className="mb4 ml-auto mr-auto" style={{ maxWidth: 360 }}>
            {t`Necesitaras un administrador para crear una cuenta de Metabase antes de que puedas usar Google para iniciar sesión.`}
          </p>

          <BackToLogin />
        </div>
      </div>
    </div>
    <AuthScene />
  </div>
);

export default GoogleNoAccount;
