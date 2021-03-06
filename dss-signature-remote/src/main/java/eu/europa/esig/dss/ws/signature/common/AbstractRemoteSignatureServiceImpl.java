/**
 * DSS - Digital Signature Services
 * Copyright (C) 2015 European Commission, provided under the CEF programme
 * 
 * This file is part of the "DSS - Digital Signature Services" project.
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */
package eu.europa.esig.dss.ws.signature.common;

import java.io.ByteArrayInputStream;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import eu.europa.esig.dss.AbstractSignatureParameters;
import eu.europa.esig.dss.asic.cades.ASiCWithCAdESSignatureParameters;
import eu.europa.esig.dss.asic.cades.ASiCWithCAdESTimestampParameters;
import eu.europa.esig.dss.asic.xades.ASiCWithXAdESSignatureParameters;
import eu.europa.esig.dss.cades.CAdESSignatureParameters;
import eu.europa.esig.dss.cades.signature.CAdESTimestampParameters;
import eu.europa.esig.dss.enumerations.ASiCContainerType;
import eu.europa.esig.dss.enumerations.CommitmentType;
import eu.europa.esig.dss.enumerations.CommitmentTypeEnum;
import eu.europa.esig.dss.enumerations.SignatureForm;
import eu.europa.esig.dss.enumerations.TimestampContainerForm;
import eu.europa.esig.dss.model.BLevelParameters;
import eu.europa.esig.dss.model.DSSException;
import eu.europa.esig.dss.model.InMemoryDocument;
import eu.europa.esig.dss.model.Policy;
import eu.europa.esig.dss.model.SerializableSignatureParameters;
import eu.europa.esig.dss.model.SignatureValue;
import eu.europa.esig.dss.model.SignerLocation;
import eu.europa.esig.dss.model.TimestampParameters;
import eu.europa.esig.dss.model.x509.CertificateToken;
import eu.europa.esig.dss.pades.DSSFileFont;
import eu.europa.esig.dss.pades.DSSFont;
import eu.europa.esig.dss.pades.PAdESSignatureParameters;
import eu.europa.esig.dss.pades.PAdESTimestampParameters;
import eu.europa.esig.dss.pades.SignatureImageParameters;
import eu.europa.esig.dss.pades.SignatureImageTextParameters;
import eu.europa.esig.dss.utils.Utils;
import eu.europa.esig.dss.ws.converter.ColorConverter;
import eu.europa.esig.dss.ws.converter.RemoteCertificateConverter;
import eu.europa.esig.dss.ws.converter.RemoteDocumentConverter;
import eu.europa.esig.dss.ws.dto.RemoteCertificate;
import eu.europa.esig.dss.ws.dto.SignatureValueDTO;
import eu.europa.esig.dss.ws.signature.dto.parameters.RemoteBLevelParameters;
import eu.europa.esig.dss.ws.signature.dto.parameters.RemoteSignatureImageParameters;
import eu.europa.esig.dss.ws.signature.dto.parameters.RemoteSignatureImageTextParameters;
import eu.europa.esig.dss.ws.signature.dto.parameters.RemoteSignatureParameters;
import eu.europa.esig.dss.ws.signature.dto.parameters.RemoteTimestampParameters;
import eu.europa.esig.dss.xades.XAdESSignatureParameters;
import eu.europa.esig.dss.xades.XAdESTimestampParameters;

public abstract class AbstractRemoteSignatureServiceImpl {

	protected SerializableSignatureParameters getASiCSignatureParameters(ASiCContainerType asicContainerType,
			SignatureForm signatureForm) {
		switch (signatureForm) {
		case CAdES:
			ASiCWithCAdESSignatureParameters asicWithCAdESParameters = new ASiCWithCAdESSignatureParameters();
			asicWithCAdESParameters.aSiC().setContainerType(asicContainerType);
			return asicWithCAdESParameters;
		case XAdES:
			ASiCWithXAdESSignatureParameters asicWithXAdESParameters = new ASiCWithXAdESSignatureParameters();
			asicWithXAdESParameters.aSiC().setContainerType(asicContainerType);
			return asicWithXAdESParameters;
		default:
			throw new DSSException("Unrecognized format (only XAdES or CAdES are allowed with ASiC) : " + signatureForm);
		}
	}

	@SuppressWarnings("unchecked")
	protected SerializableSignatureParameters createParameters(RemoteSignatureParameters remoteParameters) {
		SerializableSignatureParameters parameters = null;
		ASiCContainerType asicContainerType = remoteParameters.getAsicContainerType();
		SignatureForm signatureForm = remoteParameters.getSignatureLevel().getSignatureForm();
		if (asicContainerType != null) {
			parameters = getASiCSignatureParameters(asicContainerType, signatureForm);
		} else {
			switch (signatureForm) {
			case CAdES:
				parameters = new CAdESSignatureParameters();
				break;
			case PAdES:
				PAdESSignatureParameters padesParams = new PAdESSignatureParameters();
				padesParams.setContentSize(9472 * 2); // double reserved space for signature
				padesParams.setImageParameters(this.toImageParameters(remoteParameters.getImageParameters()));
				parameters = padesParams;
				break;
			case XAdES:
				parameters = new XAdESSignatureParameters();
				break;
			default:
				throw new DSSException("Unsupported signature form : " + signatureForm);
			}
		}

		if (parameters instanceof AbstractSignatureParameters<?>) {
			AbstractSignatureParameters<TimestampParameters> abstractSignatureParameters = (AbstractSignatureParameters<TimestampParameters>) parameters;
			fillParameters(abstractSignatureParameters, remoteParameters);
			return abstractSignatureParameters;
		}

		return parameters;
	}

	protected void fillParameters(AbstractSignatureParameters<TimestampParameters> parameters, RemoteSignatureParameters remoteParameters) {
		parameters.setBLevelParams(toBLevelParameters(remoteParameters.getBLevelParams()));
		parameters.setDetachedContents(RemoteDocumentConverter.toDSSDocuments(remoteParameters.getDetachedContents()));
		parameters.setDigestAlgorithm(remoteParameters.getDigestAlgorithm());
		parameters.setEncryptionAlgorithm(remoteParameters.getEncryptionAlgorithm());
		parameters.setMaskGenerationFunction(remoteParameters.getMaskGenerationFunction());
		parameters.setReferenceDigestAlgorithm(remoteParameters.getReferenceDigestAlgorithm());
		parameters.setSignatureLevel(remoteParameters.getSignatureLevel());
		parameters.setSignaturePackaging(remoteParameters.getSignaturePackaging());
		if (remoteParameters.getContentTimestamps() != null) {
			parameters.setContentTimestamps(TimestampTokenConverter.toTimestampTokens(remoteParameters.getContentTimestamps()));
		}
		parameters.setSignatureTimestampParameters(toTimestampParameters(remoteParameters.getSignatureTimestampParameters(), 
				remoteParameters.getSignatureLevel().getSignatureForm(), remoteParameters.getAsicContainerType()));
		parameters.setArchiveTimestampParameters(toTimestampParameters(remoteParameters.getArchiveTimestampParameters(), 
				remoteParameters.getSignatureLevel().getSignatureForm(), remoteParameters.getAsicContainerType()));
		parameters.setContentTimestampParameters(toTimestampParameters(remoteParameters.getContentTimestampParameters(), 
				remoteParameters.getSignatureLevel().getSignatureForm(), remoteParameters.getAsicContainerType()));
		parameters.setSignWithExpiredCertificate(remoteParameters.isSignWithExpiredCertificate());
		parameters.setGenerateTBSWithoutCertificate(remoteParameters.isGenerateTBSWithoutCertificate());

		RemoteCertificate signingCertificate = remoteParameters.getSigningCertificate();
		if (signingCertificate != null) { // extends do not require signing certificate
			CertificateToken certificateToken = RemoteCertificateConverter.toCertificateToken(signingCertificate);
			parameters.setSigningCertificate(certificateToken);
		}

		List<RemoteCertificate> remoteCertificateChain = remoteParameters.getCertificateChain();
		if (Utils.isCollectionNotEmpty(remoteCertificateChain)) {
			parameters.setCertificateChain(RemoteCertificateConverter.toCertificateTokens(remoteCertificateChain));
		}
	}
	
	private BLevelParameters toBLevelParameters(RemoteBLevelParameters remoteBLevelParameters) {
		BLevelParameters bLevelParameters = new BLevelParameters();
		bLevelParameters.setClaimedSignerRoles(remoteBLevelParameters.getClaimedSignerRoles());
		if (remoteBLevelParameters.getCommitmentTypeIndications() != null) {
			bLevelParameters.setCommitmentTypeIndications(toCommitmentTypeList(remoteBLevelParameters.getCommitmentTypeIndications()));
		}
		bLevelParameters.setSigningDate(remoteBLevelParameters.getSigningDate());
		bLevelParameters.setTrustAnchorBPPolicy(remoteBLevelParameters.isTrustAnchorBPPolicy());
		
		Policy policy = new Policy();
		policy.setDescription(remoteBLevelParameters.getPolicyDescription());
		policy.setDigestAlgorithm(remoteBLevelParameters.getPolicyDigestAlgorithm());
		policy.setDigestValue(remoteBLevelParameters.getPolicyDigestValue());
		policy.setId(remoteBLevelParameters.getPolicyId());
		policy.setQualifier(remoteBLevelParameters.getPolicyQualifier());
		policy.setSpuri(remoteBLevelParameters.getPolicySpuri());
		bLevelParameters.setSignaturePolicy(policy);
		
		SignerLocation signerLocation = new SignerLocation();
		signerLocation.setCountry(remoteBLevelParameters.getSignerLocationCountry());
		signerLocation.setLocality(remoteBLevelParameters.getSignerLocationLocality());
		signerLocation.setPostalAddress(remoteBLevelParameters.getSignerLocationPostalAddress());
		signerLocation.setPostalCode(remoteBLevelParameters.getSignerLocationPostalCode());
		signerLocation.setStateOrProvince(remoteBLevelParameters.getSignerLocationStateOrProvince());
		signerLocation.setStreet(remoteBLevelParameters.getSignerLocationStreet());
		if (!signerLocation.isEmpty()) {
			bLevelParameters.setSignerLocation(signerLocation);
		}
		
		return bLevelParameters;
	}
	
	protected TimestampParameters toTimestampParameters(RemoteTimestampParameters remoteTimestampParameters) {
		TimestampContainerForm timestampForm = remoteTimestampParameters.getTimestampContainerForm();
		if (timestampForm != null) {
			switch (timestampForm) {
				case PDF:
					return toTimestampParameters(remoteTimestampParameters, SignatureForm.PAdES, null);
				case ASiC_E:
					return toTimestampParameters(remoteTimestampParameters, SignatureForm.CAdES, ASiCContainerType.ASiC_E);
				case ASiC_S:
					return toTimestampParameters(remoteTimestampParameters, SignatureForm.CAdES, ASiCContainerType.ASiC_S);
				default:
					throw new DSSException(String.format("Unsupported timestamp container form [%s]", timestampForm.getReadable()));
			}
		} else {
			throw new DSSException("Timestamp container form is not defined!");
		}
	}
	
	protected TimestampParameters toTimestampParameters(RemoteTimestampParameters remoteTimestampParameters, 
			SignatureForm signatureForm, ASiCContainerType asicContainerType) {
		TimestampParameters timestampParameters;
		if (asicContainerType != null) {
			switch (signatureForm) {
				case CAdES:
					ASiCWithCAdESTimestampParameters asicWithCAdESTimestampParameters = new ASiCWithCAdESTimestampParameters(
							remoteTimestampParameters.getDigestAlgorithm());
					asicWithCAdESTimestampParameters.aSiC().setContainerType(asicContainerType);
					timestampParameters = asicWithCAdESTimestampParameters;
					break;
				case XAdES:
					timestampParameters = new XAdESTimestampParameters(remoteTimestampParameters.getDigestAlgorithm(), 
							remoteTimestampParameters.getCanonicalizationMethod());
					break;
				default:
					throw new DSSException(String.format("Unsupported signature form [%s] for asic container type [%s]", signatureForm, asicContainerType));
			}
		} else {
			switch (signatureForm) {
				case CAdES:
					timestampParameters = new CAdESTimestampParameters(remoteTimestampParameters.getDigestAlgorithm());
					break;
				case PAdES:
					timestampParameters = new PAdESTimestampParameters(remoteTimestampParameters.getDigestAlgorithm());
					break;
				case XAdES:
					timestampParameters = new XAdESTimestampParameters(remoteTimestampParameters.getDigestAlgorithm(), 
							remoteTimestampParameters.getCanonicalizationMethod());
					break;
				default:
					throw new DSSException("Unsupported signature form : " + signatureForm);
			}
		}
		return timestampParameters;
	}
	
	protected SignatureValue toSignatureValue(SignatureValueDTO signatureValueDTO) {
		return new SignatureValue(signatureValueDTO.getAlgorithm(), signatureValueDTO.getValue());
	}
	
	protected List<CommitmentType> toCommitmentTypeList(List<CommitmentTypeEnum> commitmentTypeEnums) {
		if (Utils.isCollectionNotEmpty(commitmentTypeEnums)) {
			return commitmentTypeEnums.stream().map(obj -> (CommitmentType) obj).collect(Collectors.toList());
		}
		return Collections.emptyList();
	}

	private SignatureImageParameters toImageParameters(final RemoteSignatureImageParameters remoteImageParameters) {
		if (remoteImageParameters == null) {
			return null;
		}

		final SignatureImageParameters imageParameters = new SignatureImageParameters();
		// alignmentHorizontal
		if (remoteImageParameters.getAlignmentHorizontal() != null) {
			imageParameters.setAlignmentHorizontal(remoteImageParameters.getAlignmentHorizontal());
		}
		// alignmentVertical
		if (remoteImageParameters.getAlignmentVertical() != null) {
			imageParameters.setAlignmentVertical(remoteImageParameters.getAlignmentVertical());
		}
		// backgroundColor
		if (remoteImageParameters.getBackgroundColor() != null) {
			imageParameters.setBackgroundColor(ColorConverter.toColor(remoteImageParameters.getBackgroundColor()));
		}
		// dpi
		imageParameters.setDpi(remoteImageParameters.getDpi());
		// height
		if (remoteImageParameters.getHeight() != null) {
			imageParameters.setHeight(remoteImageParameters.getHeight());
		}
		// image
		if (remoteImageParameters.getImage() != null && remoteImageParameters.getImage().getBytes() != null && remoteImageParameters.getImage().getName() != null) {
			imageParameters.setImage(new InMemoryDocument(remoteImageParameters.getImage().getBytes(), remoteImageParameters.getImage().getName()));
		}
		// page
		if (remoteImageParameters.getPage() != null) {
			imageParameters.setPage(remoteImageParameters.getPage());
		}
		// rotation
		if (remoteImageParameters.getRotation() != null) {
			imageParameters.setRotation(remoteImageParameters.getRotation());
		}
		// textParameters
		imageParameters.setTextParameters(this.toTextParameters(remoteImageParameters.getTextParameters()));
		// width
		if (remoteImageParameters.getWidth() != null) {
			imageParameters.setWidth(remoteImageParameters.getWidth());
		}
		// xAxis
		if (remoteImageParameters.getxAxis() != null) {
			imageParameters.setxAxis(remoteImageParameters.getxAxis());
		}
		// yAxis
		if (remoteImageParameters.getyAxis() != null) {
			imageParameters.setyAxis(remoteImageParameters.getyAxis());
		}
		// zoom
		if (remoteImageParameters.getZoom() != null) {
			imageParameters.setZoom(remoteImageParameters.getZoom());
		}

		return imageParameters;
	}

	private SignatureImageTextParameters toTextParameters(final RemoteSignatureImageTextParameters remoteTextParameters){
		if (remoteTextParameters == null) {
			return null;
		}

		final SignatureImageTextParameters textParameters = new SignatureImageTextParameters();
		// backgroundColor
		if (remoteTextParameters.getBackgroundColor() != null) {
			textParameters.setBackgroundColor(ColorConverter.toColor(remoteTextParameters.getBackgroundColor()));
		}
		// font
		if (remoteTextParameters.getFont() != null && remoteTextParameters.getFont().getBytes() != null) {
			textParameters.setFont(new DSSFileFont(new ByteArrayInputStream(remoteTextParameters.getFont().getBytes())));
		}
		// size
		if (remoteTextParameters.getSize() != null) {
			DSSFont font = textParameters.getFont();
			font.setSize(remoteTextParameters.getSize());
		}
		// padding
		if (remoteTextParameters.getPadding() != null) {
			textParameters.setPadding(remoteTextParameters.getPadding());
		}
		// signerTextHorizontalAlignment
		if (remoteTextParameters.getSignerTextHorizontalAlignment() != null) {
			textParameters.setSignerTextHorizontalAlignment(remoteTextParameters.getSignerTextHorizontalAlignment());
		}
		// signerTextPosition
		if (remoteTextParameters.getSignerTextPosition() != null) {
			textParameters.setSignerTextPosition(remoteTextParameters.getSignerTextPosition());
		}
		// signerTextVerticalAlignment
		if (remoteTextParameters.getSignerTextVerticalAlignment() != null) {
			textParameters.setSignerTextVerticalAlignment(remoteTextParameters.getSignerTextVerticalAlignment());
		}
		// text
		textParameters.setText(remoteTextParameters.getText());
		// textColor
		if (remoteTextParameters.getTextColor() != null) {
			textParameters.setTextColor(ColorConverter.toColor(remoteTextParameters.getTextColor()));
		}

		return textParameters;
	}



}